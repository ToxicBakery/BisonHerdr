package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Starts and stops the USB and NFC discovery of security keys. The activity supplies the implementation with
 * `YubiKitManager`, because the NFC discovery needs an activity in the foreground. The discovery gives each key that
 * it finds to [SecurityKeyHub].
 */
interface SecurityKeyDiscovery {
    /** Starts the USB and NFC discovery. */
    fun start()

    /** Stops the USB and NFC discovery. */
    fun stop()
}

/**
 * Keeps the security keys that the discovery of an activity finds, and gives a key to a [YubiKeyFido2Signer] call.
 *
 * - An activity runs [hostDiscovery] while it is in the foreground. [hasHost] is true only then. Without a host,
 *   the app cannot find a key or ask for a touch, so a signer call stops at once.
 * - The host starts the discovery only while a signer call runs ([withDiscovery]). Thus the app does not take the
 *   NFC reader and does not ask for the USB permission when no call needs a key.
 * - A USB key stays available until the discovery tells that it closed, or until the discovery stops. An NFC tap
 *   gives the key to the next [awaitDevice] call only.
 */
class SecurityKeyHub {
    private val hostCount = MutableStateFlow(0)
    private val activeRequests = MutableStateFlow(0)
    private val usbDevice = MutableStateFlow<YubiKeyDevice?>(null)
    private val nfcDevices = Channel<YubiKeyDevice>(Channel.CONFLATED)

    /** True while one or more activities run [hostDiscovery]. */
    val hasHost: Boolean
        get() = hostCount.value > 0

    /**
     * Runs [discovery] while a signer call needs a key, until the caller cancels this function. An activity calls
     * this function while it is in the foreground. When the function stops, it stops the discovery and forgets the
     * keys that the discovery found.
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
                    forgetDevices()
                }
            }
        } finally {
            if (started) {
                discovery.stop()
                forgetDevices()
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
     * accepts. The caller sets the time limit.
     */
    suspend fun awaitDevice(transport: HardwareKeyTransport): YubiKeyDevice {
        val sources = buildList {
            if (transport != HardwareKeyTransport.NfcOnly) add(usbDevice.filterNotNull())
            if (transport != HardwareKeyTransport.UsbOnly) add(nfcDevices.receiveAsFlow())
        }
        return merge(*sources.toTypedArray()).first()
    }

    private fun forgetDevices() {
        usbDevice.value = null
        forgetNfcTap()
    }

    /** Removes the NFC tap that the channel keeps. The channel keeps one tap at most. */
    private fun forgetNfcTap() {
        nfcDevices.tryReceive()
    }
}
