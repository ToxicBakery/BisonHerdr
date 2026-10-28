package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Checks the manifest entries of [BisonSessionService] and the backup rules of the app.
 *
 * `startForeground` throws when it passes a type that the manifest does not declare. Thus the
 * manifest type and the type in [BisonSessionService] must agree.
 */
class SessionServiceManifestTest {

    private val manifest: Document = parse(sourceFile("src/main/AndroidManifest.xml"))

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

    /** Android 17 stops each connection to the local network when an app that targets API 37 does not have it. */
    @Test
    fun manifestDeclaresTheLocalNetworkPermission() {
        val permissions = elements("uses-permission").map { element -> element.androidAttribute("name") }

        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in permissions)
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

    /**
     * Backups keep the machine list, the settings, and the known hosts, because they hold no secret. The include
     * lists keep all other files out, for example the temporary files and the corrupt copies. The encrypted
     * passwords are in `noBackupFilesDir`, which backups never read.
     */
    @Test
    fun backupRulesIncludeOnlyTheStoreFilesThatHoldNoSecret() {
        val application = elements("application").single()
        assertEquals("true", application.androidAttribute("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.androidAttribute("dataExtractionRules"))

        val rules = parse(sourceFile("src/main/res/xml/data_extraction_rules.xml"))
        val storeFiles = listOf(
            MachineStore.MACHINES_FILE_NAME,
            AppSettingsStore.SETTINGS_FILE_NAME,
            KnownHostsStore.KNOWN_HOSTS_FILE_NAME,
        )
        for (sectionName in listOf("cloud-backup", "device-transfer")) {
            val section = rules.elements(sectionName).single()
            val includes = section.elements("include").map { it.getAttribute("domain") to it.getAttribute("path") }
            assertEquals(storeFiles.map { "file" to it }, includes, sectionName)
            assertTrue(section.elements("exclude").isEmpty(), sectionName)
        }
        assertEquals("true", rules.elements("cloud-backup").single().getAttribute("disableIfNoEncryptionCapabilities"))
    }

    private fun elements(tagName: String): List<Element> = manifest.elements(tagName)

    private fun Document.elements(tagName: String): List<Element> = getElementsByTagName(tagName).toElements()

    private fun Element.elements(tagName: String): List<Element> = getElementsByTagName(tagName).toElements()

    private fun NodeList.toElements(): List<Element> = (0 until length).map { index -> item(index) as Element }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun parse(file: File): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun sourceFile(path: String): File =
        // Gradle runs the unit tests in the module directory. The second path is for a run from the root.
        listOf(File(path), File("app/android/$path")).first { file -> file.isFile }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
