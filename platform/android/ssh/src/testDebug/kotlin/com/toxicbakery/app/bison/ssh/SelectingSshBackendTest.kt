package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests that [SelectingSshBackend] selects the backend from the machine id only, and that it keeps the
 * backend that [SelectingSshBackend.authenticate] selected until [SelectingSshBackend.disconnect].
 */
class SelectingSshBackendTest {

    private val real = RecordingSshBackend()
    private val simulated = RecordingSshBackend()
    private val fakeIds = mutableSetOf(FAKE_ID)
    private val backend = SelectingSshBackend(
        real = real,
        simulated = simulated,
        isSimulated = { it in fakeIds },
    )

    @Test
    fun realHostNamesWithIdsThatAreNotFakeGoOnlyToTheRealBackend() = runTest {
        val machines = listOf(
            machine(id = "host-gpu-box-tailnet-ts-net-ian", hostname = "gpu-box.tailnet.ts.net"),
            machine(id = "host-studio-local-ian", hostname = "studio.local"),
            machine(id = "host-100-64-0-12-ian", hostname = "100.64.0.12"),
        )

        machines.forEach { callAllMethods(it) }

        assertEquals(machines.flatMap { expectedCalls(it.id) }, real.calls)
        assertTrue(simulated.calls.isEmpty(), "The simulated backend got ${simulated.calls}")
    }

    @Test
    fun fakeIdGoesOnlyToTheSimulatedBackend() = runTest {
        val workbox = machine(id = FAKE_ID, hostname = "100.64.0.12")

        callAllMethods(workbox)

        assertEquals(expectedCalls(FAKE_ID), simulated.calls)
        assertTrue(real.calls.isEmpty(), "The real backend got ${real.calls}")
    }

    @Test
    fun callsAfterAuthenticateGoToTheRecordedBackend() = runTest {
        val machine = machine(id = "demo-late", hostname = "demo-late.tailnet.ts.net")
        val route = ProxyJumpTunnel().resolveRoute(machine)
        backend.authenticate(machine, route)

        // The id becomes fake after the login. The open session is on the real backend.
        fakeIds += machine.id
        backend.resize(machine.id, cols = 80u, rows = 24u)
        backend.disconnect(machine.id)

        assertEquals(
            listOf("authenticate demo-late", "resize demo-late", "disconnect demo-late"),
            real.calls,
        )
        assertTrue(simulated.calls.isEmpty(), "The simulated backend got ${simulated.calls}")
        assertSame(simulated, backend.backendFor(machine.id))
    }

    @Test
    fun newSelectionAtAuthenticateDisconnectsThePreviousBackend() = runTest {
        val machine = machine(id = "demo-switch", hostname = "demo-switch.local")
        val route = ProxyJumpTunnel().resolveRoute(machine)
        backend.authenticate(machine, route)

        fakeIds += machine.id
        backend.authenticate(machine, route)

        assertEquals(listOf("authenticate demo-switch", "disconnect demo-switch"), real.calls)
        assertEquals(listOf("authenticate demo-switch"), simulated.calls)
        assertSame(simulated, backend.backendFor(machine.id))
    }

    /** Calls each of the 7 [SshBackend] methods once for [machine], with [SshBackend.authenticate] first. */
    private suspend fun callAllMethods(machine: HostMachine) {
        val route = ProxyJumpTunnel().resolveRoute(machine)
        backend.authenticate(machine, route)
        backend.negotiateBackbone(machine, route)
        backend.openSurface(machine, route, ActiveBackbone.SshEndpoint(rttMs = 1)) { true }
        backend.sendEndpointCommand(machine, route, HerdrRpcBuilder.workspaceFocus("w1"), isMoshActive = false)
        backend.sendInput(machine, route, "w1:p1", ClientPaneInputEvent.TextCommit("ls"))
        backend.resize(machine.id, cols = 80u, rows = 24u)
        backend.disconnect(machine.id)
    }

    private fun expectedCalls(machineId: String): List<String> =
        listOf(
            "authenticate",
            "negotiateBackbone",
            "openSurface",
            "sendEndpointCommand",
            "sendInput",
            "resize",
            "disconnect",
        ).map { "$it $machineId" }

    private fun machine(id: String, hostname: String): HostMachine =
        HostMachine(id = id, name = id, hostname = hostname, username = "ian")

    private companion object {
        const val FAKE_ID = "workbox"
    }
}
