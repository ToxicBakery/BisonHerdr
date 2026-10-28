package com.toxicbakery.app.bison.activity

import android.app.Activity
import com.toxicbakery.app.bison.ssh.SecurityKeyDiscovery
import com.toxicbakery.app.bison.ssh.SecurityKeyHub
import com.toxicbakery.logging.Arbor
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.Transport

/**
 * The USB discovery of security keys with [YubiKitManager]. Each key that it finds goes to [hub].
 *
 * The default [UsbConfiguration] accepts only YubiKeys (the Yubico vendor ID), so that the app does not ask for the
 * USB permission of a different USB device. It asks the user for the USB permission of a YubiKey that the app has no
 * permission for. The system shows that question in a dialog that pauses the activity, so the activity runs this
 * discovery while it is started, not only while it is resumed. Thus a pause does not stop the discovery and does not
 * show the question again.
 */
internal class YubiKeyUsbDiscovery(
    private val yubiKitManager: YubiKitManager,
    private val hub: SecurityKeyHub,
) : SecurityKeyDiscovery {

    override val transport: Transport = Transport.USB

    override fun start() {
        yubiKitManager.startUsbDiscovery(UsbConfiguration()) { device ->
            hub.onUsbDevice(device)
            device.setOnClosed { hub.onUsbDeviceClosed(device) }
        }
    }

    override fun stop() {
        yubiKitManager.stopUsbDiscovery()
    }
}

/**
 * The NFC discovery of security keys for [activity] with [YubiKitManager]. Each tap goes to [hub].
 *
 * The NFC reader mode needs a resumed activity, so the activity runs this discovery only while it is resumed. The
 * discovery needs NFC on. Without NFC, the USB discovery still works.
 */
internal class YubiKeyNfcDiscovery(
    private val activity: Activity,
    private val yubiKitManager: YubiKitManager,
    private val hub: SecurityKeyHub,
) : SecurityKeyDiscovery {

    override val transport: Transport = Transport.NFC

    override fun start() {
        try {
            yubiKitManager.startNfcDiscovery(NfcConfiguration(), activity) { device -> hub.onNfcDevice(device) }
        } catch (error: NfcNotAvailable) {
            log.d { "No NFC discovery: ${error.message}" }
        }
    }

    override fun stop() {
        yubiKitManager.stopNfcDiscovery(activity)
    }

    private companion object {
        val log = Arbor.tag("YubiKeyNfcDiscovery")
    }
}
