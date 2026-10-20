package com.toxicbakery.app.bison.common.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * The narrowest container that shows two panes side by side (Material 3 medium window breakpoint).
 */
val TWO_PANE_MIN_WIDTH: Dp = 600.dp

/**
 * True when a screen container of [maxWidth] by [maxHeight] shows two panes side by side.
 */
fun fitsTwoPanes(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth > maxHeight && maxWidth >= TWO_PANE_MIN_WIDTH

internal val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(ScreenKeyMachineFederation::class, ScreenKeyMachineFederation.serializer())
            subclass(ScreenKeyWorkspaceTerminal::class, ScreenKeyWorkspaceTerminal.serializer())
            subclass(ScreenKeyConnectingMachine::class, ScreenKeyConnectingMachine.serializer())
            subclass(ScreenKeyMachineConfigure::class, ScreenKeyMachineConfigure.serializer())
        }
    }
}

class NavigationController(
    private val backStack: NavBackStack<NavKey>,
) {
    val currentScreen: NavKey?
        get() = backStack.lastOrNull()

    fun goTo(screenKey: ScreenKey) {
        if (backStack.lastOrNull() != screenKey) {
            backStack.add(screenKey)
        }
    }

    fun replaceWith(screenKey: ScreenKey) {
        if (backStack.isNotEmpty()) {
            backStack.removeLastOrNull()
        }
        backStack.add(screenKey)
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    fun navigateToWorkspaceTerminal(machineId: String, workspaceId: String? = null) {
        val target = ScreenKeyWorkspaceTerminal(machineId = machineId, workspaceId = workspaceId)
        val current = backStack.lastOrNull()
        if (current == target) {
            return
        }
        if (current is ScreenKeyWorkspaceTerminal || current is ScreenKeyConnectingMachine) {
            backStack.removeLastOrNull()
        }
        if (backStack.lastOrNull() != target) {
            backStack.add(target)
        }
    }
}
