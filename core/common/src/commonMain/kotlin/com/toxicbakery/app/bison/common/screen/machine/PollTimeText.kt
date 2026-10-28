package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.runtime.Composable
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.transport_poll_time_ms
import com.toxicbakery.app.bison.resources.transport_poll_time_unmeasured
import org.jetbrains.compose.resources.stringResource

/**
 * Returns the text of the poll time of [backbone] ([ActiveBackbone.rttMs]). The poll time includes the run time of
 * the Herdr commands on the host, so the text does not call it a round trip time. Before the app measures the first
 * poll, the text has no value.
 */
@Composable
fun pollTimeText(backbone: ActiveBackbone): String =
    if (backbone.rttMs == ActiveBackbone.UNMEASURED_RTT_MS) {
        stringResource(Res.string.transport_poll_time_unmeasured)
    } else {
        stringResource(Res.string.transport_poll_time_ms, backbone.rttMs)
    }
