package app.bucketlist

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bucketlist.core.ApiException
import bucketlist.core.BucketItem
import bucketlist.core.BucketSession
import bucketlist.core.ListSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.crypto.Cipher

private const val AUTO_LOCK_DELAY_MS = 30_000L
private const val MIN_PASSPHRASE_LENGTH = 8
private const val DECLINED_KEY = "declined"

class BucketViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()
    private val environment = BucketEnvironment.get(application)
    private val biometricPreferences = application.getSharedPreferences("biometric", Context.MODE_PRIVATE)

    var unlocked by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var snapshot by mutableStateOf(ListSnapshot.EMPTY)
        private set
    val serverConfigurable = environment.serverConfigurable
    var serverUrl by mutableStateOf(environment.serverUrl)
        private set
    var hasSignedIn by mutableStateOf(environment.hasSignedIn())
        private set
    var biometricEnabled by mutableStateOf(environment.secrets.hasBiometricKey())
        private set
    var offerBiometric by mutableStateOf(false)
        private set

    private var session: BucketSession? = null
    private var lockJob: Job? = null
    private var loadGeneration = 0

    fun unlock(passphrase: CharArray, setupToken: String?, confirmation: CharArray?, server: String? = null) {
        if (setupToken != null) {
            if (passphrase.size < MIN_PASSPHRASE_LENGTH) return fail("Use at least $MIN_PASSPHRASE_LENGTH characters")
            if (!passphrase.contentEquals(confirmation ?: CharArray(0))) return fail("Passkeys do not match")
        }
        if (server != null && !applyServer(server)) return
        if (!environment.hasServer()) return fail("Enter the server address")
        busy = true
        message = null
        viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    val api = environment.newApi()
                    val result = if (setupToken != null) BucketSession.createAccount(api, setupToken, passphrase) else BucketSession.unlock(api, passphrase)
                    environment.rememberAuthKey(result.authKey)
                    result.authKey.fill(0)
                    result.session
                }
                hasSignedIn = true
                biometricEnabled = environment.secrets.hasBiometricKey()
                onUnlocked(opened)
                offerBiometric = !biometricEnabled && biometricAvailable() && !biometricPreferences.getBoolean(DECLINED_KEY, false)
            } catch (error: Exception) {
                Log.w(LOG_TAG, "unlock failed", error)
                message = describeFailure(error, Stage.UNLOCK)
            } finally {
                passphrase.fill(' ')
                confirmation?.fill(' ')
                busy = false
            }
        }
    }

    private fun applyServer(input: String): Boolean {
        try {
            if (environment.changeServer(input)) {
                serverUrl = environment.serverUrl
                hasSignedIn = false
                biometricEnabled = false
            }
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid server address")
            return false
        } catch (error: IOException) {
            fail("Could not save the server address")
            return false
        }
        return true
    }

    private fun onUnlocked(opened: BucketSession) {
        session = opened
        unlocked = true
        refresh()
    }

    fun biometricAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun prepareBiometricEnrollment(): Cipher? =
        runCatching { environment.secrets.biometricEncryptCipher() }.onFailure { Log.w(LOG_TAG, "cannot prepare fingerprint key", it) }.getOrNull()

    fun completeBiometricEnrollment(cipher: Cipher) {
        offerBiometric = false
        val current = session ?: return
        val listKey = current.exportListKey()
        try {
            environment.secrets.storeBiometric(cipher, listKey)
            biometricEnabled = true
            message = "Fingerprint unlock is on"
        } catch (error: Exception) {
            Log.w(LOG_TAG, "storing fingerprint key failed", error)
            environment.secrets.removeBiometric()
            message = "Could not turn on fingerprint unlock"
        } finally {
            listKey.fill(0)
        }
    }

    fun declineBiometric() {
        offerBiometric = false
        biometricPreferences.edit().putBoolean(DECLINED_KEY, true).apply()
    }

    fun disableBiometric() {
        environment.secrets.removeBiometric()
        biometricEnabled = false
        message = "Fingerprint unlock is off"
    }

    fun biometricUnlockCipher(): Cipher? {
        val cipher = runCatching { environment.secrets.biometricDecryptCipher() }.onFailure { Log.w(LOG_TAG, "fingerprint key unavailable", it) }.getOrNull()
        if (cipher == null) {
            biometricEnabled = environment.secrets.hasBiometricKey()
            message = "Fingerprint unlock was reset. Use your passkey"
        }
        return cipher
    }

    fun unlockWithBiometric(cipher: Cipher) {
        busy = true
        message = null
        viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) { openWithStoredKeys(cipher) }
                onUnlocked(opened)
            } catch (error: Exception) {
                Log.w(LOG_TAG, "fingerprint unlock failed", error)
                if (error is ApiException && error.status == 401) {
                    environment.forgetSignIn()
                    biometricEnabled = false
                    message = "Fingerprint unlock was reset. Use your passkey"
                } else {
                    message = describeFailure(error, Stage.UNLOCK)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun openWithStoredKeys(cipher: Cipher): BucketSession {
        val listKey = environment.secrets.readBiometric(cipher)
        val authKey = environment.authKey()
        try {
            if (authKey == null) throw ApiException(401, "no stored sign-in")
            val api = environment.newApi()
            api.login(authKey)
            return BucketSession.withListKey(api, listKey)
        } finally {
            listKey.fill(0)
            authKey?.fill(0)
        }
    }

    fun refresh() {
        val current = session ?: return
        val generation = ++loadGeneration
        refreshing = true
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { current.load() }
                if (generation == loadGeneration && session === current) snapshot = loaded
            } catch (error: Exception) {
                if (generation == loadGeneration) report(error)
            } finally {
                if (generation == loadGeneration) refreshing = false
            }
        }
    }

    fun add(title: String, notes: String, onSaved: () -> Unit) = saveItem(onSaved) { it.add(title, notes) }

    fun edit(item: BucketItem, title: String, notes: String, onSaved: () -> Unit) = saveItem(onSaved) { it.edit(item, title, notes) }

    fun setDone(item: BucketItem, done: Boolean) = saveItem { it.setDone(item, done) }

    fun delete(item: BucketItem, onDeleted: () -> Unit) = change(onDeleted, { it.delete(item) }) { list, _ -> list.without(item.id) }

    private fun saveItem(onSaved: () -> Unit = {}, operation: (BucketSession) -> BucketItem) =
        change(onSaved, operation) { list, saved -> list.withItem(saved) }

    private fun <T> change(onSuccess: () -> Unit, operation: (BucketSession) -> T, applyResult: (ListSnapshot, T) -> ListSnapshot) {
        val current = session ?: return
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation(current) }
                if (session !== current) return@launch
                loadGeneration++
                refreshing = false
                snapshot = applyResult(snapshot, result)
                onSuccess()
            } catch (error: Exception) {
                report(error)
                if (error is ApiException && error.status == 404) refresh()
            } finally {
                saving = false
            }
        }
    }

    private fun report(error: Exception) {
        Log.w(LOG_TAG, "list change failed", error)
        message = describeFailure(error, Stage.CHANGE)
        if (signsOut(error, Stage.CHANGE)) lock()
    }

    private fun fail(text: String) {
        message = text
    }

    fun lock() {
        val ending = session
        session = null
        unlocked = false
        offerBiometric = false
        snapshot = ListSnapshot.EMPTY
        loadGeneration++
        refreshing = false
        if (ending != null) {
            Thread {
                runCatching { ending.logout() }.onFailure { Log.w(LOG_TAG, "server logout failed", it) }
            }.start()
        }
    }

    fun appStarted() {
        lockJob?.cancel()
        lockJob = null
        if (unlocked) refresh()
    }

    fun appStopped() {
        if (!unlocked) return
        lockJob?.cancel()
        lockJob = viewModelScope.launch {
            delay(AUTO_LOCK_DELAY_MS)
            lock()
        }
    }

    override fun onCleared() {
        lock()
    }
}
