package com.toxicbakery.app.bison.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<AppSettings>(encoded)
        assertEquals(settings, decoded)
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
                tailscaleMagicDns = true,
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
