package com.sakura.encryptor.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * This package ships no embedded server, so it has no pages of its own.
 *
 * The settings entry that would open them is hidden when [isBundled] is false.
 */
fun NavGraphBuilder.embeddedServerDestinations(navController: NavHostController) = Unit
