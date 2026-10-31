package com.sakura.encryptor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sakura.encryptor.ui.SakuraRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the whole app.
 *
 * Extends [FragmentActivity] rather than `ComponentActivity` because
 * `BiometricPrompt` requires a FragmentActivity host to attach its dialog.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SakuraApp).container

        // A foreground service cannot be started from the application object
        // reliably, so the notification that keeps the embedded server alive
        // is raised from here, where the app is visibly in the foreground.
        lifecycleScope.launch {
            if (container.settings.localOpenListEnabled.first()) {
                container.embeddedServer.startForegroundService(this@MainActivity)
            }
        }

        setContent {
            SakuraRoot(container)
        }
    }
}
