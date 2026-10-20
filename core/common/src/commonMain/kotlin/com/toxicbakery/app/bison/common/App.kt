package com.toxicbakery.app.bison.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.toxicbakery.app.bison.common.navigation.NavigationController
import com.toxicbakery.app.bison.common.navigation.ScreenKeyConnectingMachine
import com.toxicbakery.app.bison.common.navigation.ScreenKeyMachineConfigure
import com.toxicbakery.app.bison.common.navigation.ScreenKeyMachineFederation
import com.toxicbakery.app.bison.common.navigation.ScreenKeyWorkspaceTerminal
import com.toxicbakery.app.bison.common.navigation.navConfig
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineScreen
import com.toxicbakery.app.bison.common.screen.machine.ConnectingMachineScreen
import com.toxicbakery.app.bison.common.screen.machine.MachineFederationScreen
import com.toxicbakery.app.bison.common.screen.workspace.WorkspaceTerminalScreen
import com.toxicbakery.app.bison.common.theme.BisonTheme
import com.toxicbakery.app.bison.common.theme.resolveTheme
import com.toxicbakery.app.bison.common.version.VersionWatcher
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.rememberInstance

private val log = Arbor.tag("App")

@Composable
fun App(
    onExitRequested: () -> Unit = {},
) {
    val backStack = rememberNavBackStack(
        configuration = navConfig,
        ScreenKeyMachineFederation,
    )
    val navigationController = remember(backStack) { NavigationController(backStack) }
    val machineStore: MachineStore by rememberInstance()
    val appSettingsStore: AppSettingsStore by rememberInstance()
    val versionWatcher: VersionWatcher by rememberInstance()
    val knownMachinesCount by remember(machineStore) {
        machineStore.knownMachines.map { it.size }.distinctUntilChanged()
    }.collectAsState(initial = 0)
    val appSettings by appSettingsStore.settings.collectAsState()

    val globalPalette = remember(appSettings.themeId) {
        resolveTheme(globalThemeId = appSettings.themeId, hostOverrideThemeId = null)
    }

    LaunchedEffect(versionWatcher) {
        versionWatcher.start(this)
    }

    LaunchedEffect(knownMachinesCount) {
        log.d { "BisonHerdr fleet has $knownMachinesCount configured hosts" }
    }

    BisonTheme(palette = globalPalette) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = globalPalette.bgDeep,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    onBack = {
                        if (backStack.size == 1) {
                            onExitRequested()
                        } else {
                            backStack.removeLastOrNull()
                        }
                    },
                    entryProvider = entryProvider {
                        entry<ScreenKeyMachineFederation> {
                            MachineFederationScreen(
                                navigationController = navigationController,
                            )
                        }
                        entry<ScreenKeyWorkspaceTerminal> { screenKey ->
                            WorkspaceTerminalScreen(
                                navigationController = navigationController,
                                machineId = screenKey.machineId,
                            )
                        }
                        entry<ScreenKeyConnectingMachine> { screenKey ->
                            ConnectingMachineScreen(
                                navigationController = navigationController,
                                machineId = screenKey.machineId,
                            )
                        }
                        entry<ScreenKeyMachineConfigure> { screenKey ->
                            ConfigureMachineScreen(
                                navigationController = navigationController,
                                machineId = screenKey.machineId,
                            )
                        }
                    },
                )
            }
        }
    }
}
