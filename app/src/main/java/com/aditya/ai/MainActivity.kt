package com.aditya.ai
import android.Manifest
import android.content.Intent
import android.os.*
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.aditya.ai.svc.SyncService
import com.aditya.ai.ui.Root
class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false); private var prompting = false
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val app get() = application as AdityaApp
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (app.store.bool("secure", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        locked.value = app.store.bool("lock", false)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
        startForegroundService(Intent(this, SyncService::class.java))
        setContent { Root(app, locked.value) { unlock() } }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    private fun handle(i: Intent?) { val id = i?.getLongExtra("chat_id", 0L) ?: 0L; if (id != 0L) app.openChat.value = id }
    override fun onStart() { super.onStart(); app.visible = true; app.suppressLock = false; if (locked.value) unlock() }
    override fun onStop() { super.onStop(); app.visible = false; if (app.store.bool("lock", false) && !app.suppressLock) locked.value = true }
    private fun unlock() {
        if (prompting) return; prompting = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { prompting = false; locked.value = false }
            override fun onAuthenticationError(c: Int, m: CharSequence) { prompting = false }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock ADITYA AI").setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
    }
}
