package com.sakura.encryptor.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sakura.encryptor.ui.screens.openlist.ServerSettingsScreen
import com.sakura.encryptor.ui.screens.openlist.StorageEditScreen
import com.sakura.encryptor.ui.screens.openlist.StorageScreen

/** Pages of the embedded server: its connection settings plus storages. */
fun NavGraphBuilder.embeddedServerDestinations(navController: NavHostController) {
    composable(Routes.OPENLIST_SERVER) {
        ServerSettingsScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.OPENLIST_STORAGE) {
        StorageScreen(
            onBack = { navController.popBackStack() },
            onAddStorage = { driver ->
                navController.navigate(Routes.storageEdit(driver = driver))
            },
            onEditStorage = { id ->
                navController.navigate(Routes.storageEdit(id = id))
            },
        )
    }

    composable(
        route = Routes.STORAGE_EDIT,
        arguments = listOf(
            navArgument(Routes.STORAGE_ARG_ID) {
                type = NavType.IntType
                defaultValue = Routes.NEW_STORAGE_ID
            },
            navArgument(Routes.STORAGE_ARG_DRIVER) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val id = entry.arguments?.getInt(Routes.STORAGE_ARG_ID) ?: Routes.NEW_STORAGE_ID
        StorageEditScreen(
            storageId = id.takeIf { it != Routes.NEW_STORAGE_ID },
            driverName = entry.arguments?.getString(Routes.STORAGE_ARG_DRIVER).orEmpty(),
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
}
