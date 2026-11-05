package app.bucketlist

import android.content.Context
import bucketlist.core.BucketApi
import bucketlist.core.normaliseServerUrl
import java.io.File
import java.io.IOException

const val LOG_TAG = "BucketList"
private const val AUTH_KEY = "auth.bin"
private const val SERVER_PREFS = "server"
private const val SERVER_URL_KEY = "url"

class BucketEnvironment private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val serverPrefs = appContext.getSharedPreferences(SERVER_PREFS, Context.MODE_PRIVATE)
    val secrets = DeviceSecrets(File(appContext.filesDir, "secrets"))

    val serverConfigurable: Boolean = BuildConfig.SERVER_CONFIGURABLE

    val serverUrl: String
        get() = if (serverConfigurable) serverPrefs.getString(SERVER_URL_KEY, null) ?: BuildConfig.SERVER_URL else BuildConfig.SERVER_URL

    fun hasServer(): Boolean = serverUrl.isNotEmpty()

    fun changeServer(input: String): Boolean {
        check(serverConfigurable) { "this edition has a fixed server" }
        val url = normaliseServerUrl(input)
        val previous = serverPrefs.getString(SERVER_URL_KEY, null)
        if (url == previous) return false
        if (previous != null || hasSignedIn()) forgetSignIn()
        if (!serverPrefs.edit().putString(SERVER_URL_KEY, url).commit()) throw IOException("could not save the server address")
        return true
    }

    fun newApi(): BucketApi {
        check(hasServer()) { "no server address set" }
        return BucketApi(serverUrl)
    }

    fun authKey(): ByteArray? = secrets.get(AUTH_KEY)

    fun hasSignedIn(): Boolean = secrets.exists(AUTH_KEY)

    fun rememberAuthKey(authKey: ByteArray) {
        val previous = authKey()
        if (previous != null && !previous.contentEquals(authKey)) secrets.removeBiometric()
        secrets.put(AUTH_KEY, authKey)
    }

    fun forgetSignIn() {
        secrets.removeBiometric()
        secrets.remove(AUTH_KEY)
    }

    companion object {
        @Volatile
        private var instance: BucketEnvironment? = null

        fun get(context: Context): BucketEnvironment =
            instance ?: synchronized(this) { instance ?: BucketEnvironment(context).also { instance = it } }
    }
}
