package com.toxicbakery.app.bison.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Checks the manifest entries of [BisonSessionService].
 *
 * `startForeground` throws when it passes a type that the manifest does not declare. Thus the
 * manifest type and the type in [BisonSessionService] must agree.
 */
class SessionServiceManifestTest {

    private val manifest: Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(manifestFile())

    @Test
    fun serviceDeclaresOnlyTheSpecialUseType() {
        val service = elements("service").single { element ->
            element.androidAttribute("name") == ".service.BisonSessionService"
        }
        assertEquals("specialUse", service.androidAttribute("foregroundServiceType"))

        val subtype = elements("property").single { element ->
            element.androidAttribute("name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        }
        assertEquals("Keeps SSH terminal sessions open in the background", subtype.androidAttribute("value"))
    }

    @Test
    fun manifestDeclaresSpecialUseAndNotConnectedDevicePermission() {
        val permissions = elements("uses-permission").map { element -> element.androidAttribute("name") }

        assertTrue("android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions)
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertFalse("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in permissions)
    }

    /**
     * This version does not support hardware security keys, so the app does not ask for NFC or USB host
     * access. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds them again with FIDO2 keys.
     */
    @Test
    fun manifestDeclaresNoNfcPermissionAndNoHardwareKeyFeatures() {
        val permissions = elements("uses-permission").map { element -> element.androidAttribute("name") }
        val features = elements("uses-feature").map { element -> element.androidAttribute("name") }

        assertTrue("android.permission.INTERNET" in permissions)
        assertFalse("android.permission.NFC" in permissions)
        assertFalse("android.hardware.nfc" in features)
        assertFalse("android.hardware.usb.host" in features)
    }

    private fun elements(tagName: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tagName)
        return (0 until nodes.length).map { index -> nodes.item(index) as Element }
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun manifestFile(): File =
        // Gradle runs the unit tests in the module directory. The second path is for a run from the root.
        listOf(File("src/main/AndroidManifest.xml"), File("app/android/src/main/AndroidManifest.xml"))
            .first { file -> file.isFile }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
