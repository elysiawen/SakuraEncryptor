package com.sakura.encryptor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.sakura.encryptor.ui.SakuraRoot

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

        setContent {
            SakuraRoot(container)
        }
    }
}
