package com.sakura.encryptor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sakura.encryptor.AppContainer

/** Provides the app-wide dependency container to the composition tree. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided. Wrap the UI in CompositionLocalProvider.")
}

/** Convenience accessor for the container from any composable. */
@Composable
fun appContainer(): AppContainer = LocalAppContainer.current

/**
 * Builds a [ViewModel] wired to the container without a DI framework.
 *
 * ```kotlin
 * val vm = rememberAppViewModel { BrowseViewModel(it.aListRepository) }
 * ```
 */
@Composable
inline fun <reified VM : ViewModel> rememberAppViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
