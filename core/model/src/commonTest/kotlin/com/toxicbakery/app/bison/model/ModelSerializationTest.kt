package com.toxicbakery.app.bison.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun appSettingsDefaultsToGruvboxDarkAndSplitThumbKeys() {
        val settings = AppSettings()
        assertEquals("gruvbox", settings.themeId)
        assertEquals(13f, settings.defaultFontSizeSp)
        assertTrue(settings.autoFadeHudWhileTyping)
        assertTrue(settings.predictiveEchoUnderlines)
        assertTrue(settings.hapticFeedbackEnabled)
        assertTrue(settings.splitThumbLayoutEnabled)
        assertEquals(
            listOf("Esc", "Tab", "Ctrl", "Alt", "^C", "^B", "/", "|"),
            settings.leftThumbKeys,
        )
        assertTrue(settings.checkForUpdates)
        assertEquals(0L, settings.lastReleaseCheckEpochMs)
        assertEquals(null, settings.availableReleaseVersion)
        assertEquals(null, settings.availableReleaseUrl)
        assertEquals(null, settings.notifiedReleaseVersion)

        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertEquals(settings, decoded)

        val defaultsFromEmpty = json.decodeFromString<AppSettings>("{}")
        assertEquals(AppSettings(), defaultsFromEmpty)

        val withReleaseFields = settings.copy(
            checkForUpdates = false,
            lastReleaseCheckEpochMs = 1_760_000_000_000L,
            availableReleaseVersion = "26.42.2",
            availableReleaseUrl = "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2",
            notifiedReleaseVersion = "26.42.1",
        )
        assertEquals(withReleaseFields, json.decodeFromString<AppSettings>(json.encodeToString(withReleaseFields)))
    }

    @Test
    fun hostMachineRoundTripsAllAuthMethodsAndProxyJump() {
        val keystoreHost = HostMachine(
            id = "workbox",
            name = "workbox",
            hostname = "100.64.0.12",
            username = "ian",
            authMethod = SshAuthMethod.KeystoreKey(
                keyAlias = "workbox_ed25519",
                algorithm = KeyAlgorithm.Ed25519,
                requireBiometric = true,
            ),
            transportMode = TransportMode.AutoPreferMosh,
            moshUdpPortRange = "60000:60100",
            herdrSessionName = "main",
            proxyJump = ProxyJumpConfig(
                bastionHost = "bastion.tailnet.ts.net",
                bastionPort = 2222,
                bastionUser = "jump",
            ),
            themeOverrideId = "gruvbox",
            fontSizeSp = 14f,
        )

        val encodedKeystore = json.encodeToString(keystoreHost)
        assertEquals(keystoreHost, json.decodeFromString<HostMachine>(encodedKeystore))
        assertEquals("ian@100.64.0.12", keystoreHost.displayAddress)
        assertTrue(keystoreHost.isValidPort)
        assertEquals(60000..60100, keystoreHost.parsedMoshPortRange())

        val hardwareHost = keystoreHost.copy(
            id = "gpu-rig",
            port = 2222,
            authMethod = SshAuthMethod.HardwareSecurityKey(
                keyType = HardwareKeyType.SkSshEd25519,
                transport = HardwareKeyTransport.UsbAndNfc,
                applicationId = "ssh:bison",
            ),
            transportMode = TransportMode.SshEndpointOnly,
        )
        assertEquals("ian@100.64.0.12:2222", hardwareHost.displayAddress)
        assertEquals("sk-ssh-ed25519@openssh.com", HardwareKeyType.SkSshEd25519.opensshKeyType)
        assertEquals("sk-ecdsa-sha2-nistp256@openssh.com", HardwareKeyType.SkEcdsaSha2Nistp256.opensshKeyType)
        assertEquals(hardwareHost, json.decodeFromString<HostMachine>(json.encodeToString(hardwareHost)))

        val passwordHost = keystoreHost.copy(
            id = "cloud-vm",
            port = 0,
            authMethod = SshAuthMethod.Password(
                encryptedPasswordRef = "enc_pw_1",
                keyPassphraseRef = "enc_pass_1",
            ),
            moshUdpPortRange = "invalid",
        )
        assertFalse(passwordHost.isValidPort)
        assertEquals(60000..61000, passwordHost.parsedMoshPortRange())
        assertEquals(passwordHost, json.decodeFromString<HostMachine>(json.encodeToString(passwordHost)))
    }

    /**
     * An old version could save a machine with a hardware security key. That file has no credential fields
     * (issue #23). The file must still load, the method must not change, and the key is not enrolled.
     */
    @Test
    fun oldMachinesFileWithAHardwareSecurityKeyStillLoads() {
        val oldMachinesFile = """
            [
              {"id":"workbox","name":"Workbox","hostname":"100.64.0.12","port":22,"username":"ian",
               "authMethod":{"type":"keystore_key","keyAlias":"bison_default_ed25519","algorithm":"Ed25519",
                 "requireBiometric":false},
               "transportMode":"AutoPreferMosh","moshServerPath":null,"moshUdpPortRange":"60000:61000",
               "herdrSessionName":null,"proxyJump":null,"themeOverrideId":null,"fontSizeSp":13.0},
              {"id":"gpu-rig","name":"GPU Rig","hostname":"100.64.0.25","port":2222,"username":"ian",
               "authMethod":{"type":"hardware_security_key","keyType":"SkEcdsaSha2Nistp256","transport":"NfcOnly",
                 "applicationId":"ssh:"},
               "transportMode":"SshEndpointOnly","moshServerPath":null,"moshUdpPortRange":"60000:61000",
               "herdrSessionName":null,"proxyJump":null,"themeOverrideId":null,"fontSizeSp":13.0}
            ]
        """.trimIndent()

        val machines = json.decodeFromString<List<HostMachine>>(oldMachinesFile)

        assertEquals(listOf("workbox", "gpu-rig"), machines.map { it.id })
        assertTrue(machines[0].authMethod.isSupported(FeatureFlags()))
        val hardwareKey = assertIs<SshAuthMethod.HardwareSecurityKey>(machines[1].authMethod)
        assertEquals(
            SshAuthMethod.HardwareSecurityKey(
                keyType = HardwareKeyType.SkEcdsaSha2Nistp256,
                transport = HardwareKeyTransport.NfcOnly,
                applicationId = "ssh:",
            ),
            hardwareKey,
        )
        assertNull(hardwareKey.credentialId)
        assertNull(hardwareKey.publicKey)
        assertFalse(hardwareKey.residentKey)
        assertFalse(hardwareKey.isEnrolled)
        assertEquals(machines, json.decodeFromString<List<HostMachine>>(json.encodeToString(machines)))
    }

    /** The flag decides if the app can use a FIDO2 key. A PIV key is never supported. */
    @Test
    fun hardwareSecurityKeyIsSupportedOnlyWithTheFlagAndAFido2KeyType() {
        val off = FeatureFlags()
        val on = FeatureFlags(hardwareSecurityKeys = true)
        assertFalse(off.hardwareSecurityKeys)

        for (keyType in listOf(HardwareKeyType.SkSshEd25519, HardwareKeyType.SkEcdsaSha2Nistp256)) {
            val key = SshAuthMethod.HardwareSecurityKey(keyType = keyType)
            assertTrue(keyType.isFido2)
            assertFalse(key.isSupported(off), "$keyType with the flag off")
            assertTrue(key.isSupported(on), "$keyType with the flag on")
        }
        assertFalse(HardwareKeyType.PivSmartCard.isFido2)
        assertFalse(SshAuthMethod.HardwareSecurityKey(keyType = HardwareKeyType.PivSmartCard).isSupported(on))

        for (flags in listOf(off, on)) {
            assertTrue(SshAuthMethod.KeystoreKey().isSupported(flags))
            assertTrue(SshAuthMethod.Password().isSupported(flags))
        }
    }

    /** An enrolled key keeps its credential ID, its public key, and the resident key flag in `machines.json`. */
    @Test
    fun enrolledHardwareSecurityKeyRoundTrips() {
        val enrolled = SshAuthMethod.HardwareSecurityKey(
            keyType = HardwareKeyType.SkEcdsaSha2Nistp256,
            transport = HardwareKeyTransport.UsbOnly,
            credentialId = "AAECAwQFBgcICQ==",
            publicKey = "BAECAw==",
            residentKey = true,
        )
        assertTrue(enrolled.isEnrolled)
        assertFalse(enrolled.copy(credentialId = "").isEnrolled)
        assertFalse(enrolled.copy(publicKey = null).isEnrolled)

        val machine = HostMachine(id = "yubikey-box", name = "YubiKey Box", hostname = "10.0.0.5", username = "ian")
            .copy(authMethod = enrolled)
        val encoded = json.encodeToString(machine)
        assertTrue("\"credentialId\":\"AAECAwQFBgcICQ==\"" in encoded, encoded)
        assertTrue("\"publicKey\":\"BAECAw==\"" in encoded, encoded)
        assertTrue("\"residentKey\":true" in encoded, encoded)
        assertEquals(machine, json.decodeFromString<HostMachine>(encoded))
    }

    @Test
    fun activeBackboneAndEnumsRoundTrip() {
        val mosh: ActiveBackbone = ActiveBackbone.MoshUdp(
            udpPort = 60004,
            rttMs = 11,
            localEchoEnabled = true,
            roamedCount = 2,
        )
        val ssh: ActiveBackbone = ActiveBackbone.SshEndpoint(
            rttMs = 28,
            fallbackReason = "UDP blocked by firewall",
        )

        assertEquals(mosh, json.decodeFromString<ActiveBackbone>(json.encodeToString(mosh)))
        assertEquals(ssh, json.decodeFromString<ActiveBackbone>(json.encodeToString(ssh)))
        assertEquals(11, mosh.rttMs)
        assertEquals(28, ssh.rttMs)
        assertEquals(7, ConnectionProgress.entries.size)
        assertEquals(3, RoamingStatus.entries.size)
    }
}
