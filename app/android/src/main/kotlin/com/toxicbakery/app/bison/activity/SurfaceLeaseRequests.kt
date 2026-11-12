package com.toxicbakery.app.bison.activity

import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager

/**
 * Sends a [SshRequest.SetSurfaceLease] with [active] for each connected machine. `MainActivity` calls it from
 * `onStart` with `active = true` and from `onStop` with `active = false`.
 *
 * Call it after the change of [counter]. `SshTransportManager.setSurfaceLease` gives this order rule.
 *
 * For `active = false`, the function sends no request while [counter] still has a started activity. That activity
 * shows the app, so the leases stay on.
 */
internal suspend fun sendSurfaceLeaseRequests(
    active: Boolean,
    counter: StartedActivityCounter,
    sshStateManager: SshStateManager,
    sshRequestManager: SshRequestManager,
) {
    if (!active && counter.isInForeground()) return
    for (machineId in sshStateManager.connectedMachines.value) {
        sshRequestManager.emit(SshRequest.SetSurfaceLease(machineId = machineId, active = active))
    }
}
