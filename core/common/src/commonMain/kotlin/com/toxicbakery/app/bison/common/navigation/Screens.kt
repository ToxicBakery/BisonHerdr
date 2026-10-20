package com.toxicbakery.app.bison.common.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed class ScreenKey : NavKey

@Serializable
data object ScreenKeyMachineFederation : ScreenKey()

@Serializable
data class ScreenKeyWorkspaceTerminal(
    val machineId: String,
    val workspaceId: String? = null,
) : ScreenKey()

@Serializable
data class ScreenKeyConnectingMachine(
    val machineId: String,
) : ScreenKey()

@Serializable
data class ScreenKeyMachineConfigure(
    val machineId: String,
) : ScreenKey()
