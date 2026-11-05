package app.bucketlist

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.bucketlist.ui.BucketTheme
import app.bucketlist.ui.ListScreen
import app.bucketlist.ui.UnlockScreen
import javax.crypto.Cipher

typealias BiometricAuth = (cipher: Cipher, title: String, onSuccess: (Cipher) -> Unit) -> Unit

class MainActivity : FragmentActivity() {
    private val viewModel: BucketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            BucketTheme {
                if (viewModel.unlocked) ListScreen(viewModel, ::authenticate) else UnlockScreen(viewModel, ::authenticate)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.appStarted()
    }

    override fun onStop() {
        super.onStop()
        viewModel.appStopped()
    }

    private fun authenticate(cipher: Cipher, title: String, onSuccess: (Cipher) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess)
            }
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use passkey")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
