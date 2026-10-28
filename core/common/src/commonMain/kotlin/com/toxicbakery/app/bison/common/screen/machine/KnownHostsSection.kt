package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.known_hosts_delete
import com.toxicbakery.app.bison.resources.known_hosts_empty
import com.toxicbakery.app.bison.resources.known_hosts_title
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

/**
 * Shows the saved host keys of the saved machine [machineId] in the machine settings. Each key has a
 * delete action. The next connection after a delete asks the user to accept the key again.
 */
@Composable
internal fun KnownHostsSection(machineId: String) {
    val viewModel: KnownHostsViewModel by rememberViewModel(arg = machineId)
    val state by viewModel.state.collectAsState()
    val palette = LocalBisonPalette.current

    Surface(
        color = palette.bgSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.borderSubtle),
        modifier = Modifier.fillMaxWidth().testTag("knownHostsSection"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.known_hosts_title),
                style = MaterialTheme.typography.titleSmall,
                color = palette.accentPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.entries.isEmpty()) {
                Text(
                    text = stringResource(Res.string.known_hosts_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.fgMuted,
                )
            }
            state.entries.forEach { entry ->
                KnownHostRow(
                    entry = entry,
                    onDelete = { viewModel.onEvent(KnownHostsViewModel.Event.Delete(entry)) },
                )
            }
        }
    }
}

@Composable
private fun KnownHostRow(
    entry: KnownHost,
    onDelete: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    Surface(
        color = palette.bgElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, palette.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${entry.hostPort}  ${entry.keyType}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.fgPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                SelectionContainer {
                    Text(
                        text = entry.fingerprint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.fgMuted,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("deleteKnownHost"),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.known_hosts_delete, entry.hostPort),
                    tint = palette.accentError,
                )
            }
        }
    }
}
