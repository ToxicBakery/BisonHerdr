package com.toxicbakery.app.bison.debug

/**
 * Seam that lets a debug build answer Herdr SSH/Endpoint requests with a simulated server
 * (`FakeHerdrServer` in `:core:fake-server`), while release builds bind [NoDebugServer]
 * so zero simulated server code ships in production.
 */
interface DebugServer {

    val isEnabled: Boolean

    fun enable()

    suspend fun handleConnect(machineId: String): Boolean

    suspend fun handleDisconnect(machineId: String): Boolean

    suspend fun handleSetSurfaceLease(machineId: String, active: Boolean): Boolean

    suspend fun handleEndpointCommand(machineId: String, method: String, paramsJson: String): String?

    suspend fun handlePaneInput(machineId: String, paneId: String, inputJson: String): Boolean

    suspend fun handleResizeShell(machineId: String, cols: UShort, rows: UShort): Boolean
}

/**
 * The release binding. No simulated server exists, so every member is inert.
 */
object NoDebugServer : DebugServer {

    override val isEnabled: Boolean = false

    override fun enable() = Unit

    override suspend fun handleConnect(machineId: String): Boolean = false

    override suspend fun handleDisconnect(machineId: String): Boolean = false

    override suspend fun handleSetSurfaceLease(machineId: String, active: Boolean): Boolean = false

    override suspend fun handleEndpointCommand(
        machineId: String,
        method: String,
        paramsJson: String,
    ): String? = null

    override suspend fun handlePaneInput(
        machineId: String,
        paneId: String,
        inputJson: String,
    ): Boolean = false

    override suspend fun handleResizeShell(
        machineId: String,
        cols: UShort,
        rows: UShort,
    ): Boolean = false
}
