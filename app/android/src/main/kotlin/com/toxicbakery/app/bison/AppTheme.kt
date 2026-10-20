package com.toxicbakery.app.bison

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.toxicbakery.app.bison.common.theme.BisonTheme
import com.toxicbakery.app.bison.common.theme.BisonThemes
import com.toxicbakery.app.bison.store.AppSettingsStore
import org.kodein.di.compose.localDI
import org.kodein.di.instance

@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    val di = localDI()
    val appSettingsStore: AppSettingsStore by di.instance()
    val settings by appSettingsStore.settings.collectAsState()
    val palette = BisonThemes.byId(settings.themeId)

    BisonTheme(
        palette = palette,
        content = content,
    )
}
