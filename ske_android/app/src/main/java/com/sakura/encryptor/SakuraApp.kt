package com.sakura.encryptor

import android.app.Application
import com.sakura.encryptor.ui.i18n.initializeI18n

/**
 * Application entry point. Holds the manual dependency container
 * (no DI framework: the graph is small and explicit).
 */
class SakuraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        initializeI18n(this)
        container = AppContainer(this)
    }
}
