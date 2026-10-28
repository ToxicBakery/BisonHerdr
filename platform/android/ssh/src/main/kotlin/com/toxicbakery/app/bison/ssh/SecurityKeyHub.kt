package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.yubico.yubikit.core.Transport
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Starts and stops the discovery of security keys on one connection: USB or NFC. The activity supplies the
 * implementations with `YubiKitManager`, because the discovery needs an activity. The discovery gives each key that it
 * finds to [SecurityKeyHub].
 */
interface SecurityKeyDiscovery {
    /** The connection of the keys that this discovery finds: [Transport.USB] or [Transport.NFC]. */
    val transport: Transport

    /** Starts the discovery. */
    fun start()

    /** Stops the discovery. */
    fun stop()
}

/**
 * Keeps the security keys that the discovery of an activity finds, and gives a key to a [YubiKeyFido2Signer] call.
 *
 * - An activity runs [hostDiscovery] for each [SecurityKeyDiscovery] while it is visible. [hasHost] is true only then.
 *   Without a host, the app cannot find a key or ask for a touch, so a signer call stops at once.
 * - A host starts its discovery only while a signer call runs ([withDiscovery]). Thus the app does not take the NFC
 *   reader and does not ask for the USB permission when no call needs a key.
 * - A USB key stays available until the discovery tells that it closed, or until the USB discovery stops. An NFC tap
 *   gives the key to the next [awaitDevice] call only.
 */
class SecurityKeyHub {
    private val hostCount = MutableStateFlow(0)
    private val activeRequests = MutableStateFlow(0)
    private val usbDevice = MutableStateFlow<YubiKeyDevice?>(null)
    private val nfcDevices = Channel<YubiKeyDevice>(Channel.CONFLATED)

    /** True while one or more callers run [hostDiscovery]. */
    val hasHost: Boolean
        get() = hostCount.value > 0

    /**
     * Runs [discovery] while a signer call needs a key, until the caller cancels this function. When the discovery
     * stops, the hub forgets the keys of its [SecurityKeyDiscovery.transport]. The keys of the other connection stay.
     */
    suspend fun hostDiscovery(discovery: SecurityKeyDiscovery) {
        hostCount.update { it + 1 }
        var started = false
        try {
            activeRequests.map { it > 0 }.distinctUntilChanged().collect { active ->
                if (active && !started) {
                    discovery.start()
                    started = true
                } else if (!active && started) {
                    discovery.stop()
                    started = false
                    forgetDevices(discovery.transport)
                }
            }
        } finally {
            if (started) {
                discovery.stop()
                forgetDevices(discovery.transport)
            }
            hostCount.update { it - 1 }
        }
    }

    /** The USB discovery found [device]. It replaces the last USB key. */
    fun onUsbDevice(device: YubiKeyDevice) {
        usbDevice.value = device
    }

    /** The USB key [device] closed, for example because the user removed it. */
    fun onUsbDeviceClosed(device: YubiKeyDevice) {
        usbDevice.compareAndSet(device, null)
    }

    /** The NFC discovery found [device]. Only the last tap stays until a call takes it. */
    fun onNfcDevice(device: YubiKeyDevice) {
        nfcDevices.trySend(device)
    }

    /**
     * Runs [block] while the host runs the discovery. The first call of a series forgets an old NFC tap, so that a
     * call does not use a tap from before its prompt.
     */
    suspend fun <T> withDiscovery(block: suspend () -> T): T {
        if (activeRequests.getAndUpdate { it + 1 } == 0) forgetNfcTap()
        try {
            return block()
        } finally {
            activeRequests.update { it - 1 }
        }
    }

    /**
     * Returns the USB key at once, or waits for a USB key or an NFC tap. [transport] limits the keys that the call
     * accepts. The caller sets the time limit. When no host is left, for example because the activity stopped, the
     * function throws [HardwareKeyException.Reason.NoUi], because no discovery can find a key.
     */
    suspend fun awaitDevice(transport: HardwareKeyTransport): YubiKeyDevice {
        val sources = buildList<Flow<YubiKeyDevice?>> {
            if (transport != HardwareKeyTransport.NfcOnly) add(usbDevice.filterNotNull())
            if (transport != HardwareKeyTransport.UsbOnly) add(nfcDevices.receiveAsFlow())
            // Null tells that no host is left.
            add(hostCount.filter { it == 0 }.map { null })
        }
        return merge(*sources.toTypedArray()).first()
            ?: throw HardwareKeyException(HardwareKeyException.Reason.NoUi)
    }

    private fun forgetDevices(transport: Transport) {
        when (transport) {
            Transport.USB -> usbDevice.value = null
            Transport.NFC -> forgetNfcTap()
        }
    }

    /** Removes the NFC tap that the channel keeps. The channel keeps one tap at most. */
    private fun forgetNfcTap() {
        nfcDevices.tryReceive()
    }
}
