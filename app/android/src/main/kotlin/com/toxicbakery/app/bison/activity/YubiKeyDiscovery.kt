package com.toxicbakery.app.bison.activity

import android.app.Activity
import com.toxicbakery.app.bison.ssh.SecurityKeyDiscovery
import com.toxicbakery.app.bison.ssh.SecurityKeyHub
import com.toxicbakery.logging.Arbor
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration

/**
 * The USB and NFC discovery of security keys for [activity], with [YubiKitManager]. Each key that the discovery finds
 * goes to [hub]. [SecurityKeyHub.hostDiscovery] starts the discovery only while a security key call runs.
 *
 * - The USB discovery asks the user for the USB permission of a key that the app has no permission for.
 * - The NFC discovery takes the NFC reader of the device in the foreground. It needs NFC on. Without NFC, the USB
 *   discovery still works.
 */
internal class YubiKeyDiscovery(
    private val activity: Activity,
    private val hub: SecurityKeyHub,
    private val yubiKitManager: YubiKitManager = YubiKitManager(activity.applicationContext),
) : SecurityKeyDiscovery {

    override fun start() {
        yubiKitManager.startUsbDiscovery(UsbConfiguration()) { device ->
            hub.onUsbDevice(device)
            device.setOnClosed { hub.onUsbDeviceClosed(device) }
        }
        try {
            yubiKitManager.startNfcDiscovery(NfcConfiguration(), activity) { device -> hub.onNfcDevice(device) }
        } catch (error: NfcNotAvailable) {
            log.d { "No NFC discovery: ${error.message}" }
        }
    }

    override fun stop() {
        yubiKitManager.stopUsbDiscovery()
        yubiKitManager.stopNfcDiscovery(activity)
    }

    private companion object {
        val log = Arbor.tag("YubiKeyDiscovery")
    }
}
