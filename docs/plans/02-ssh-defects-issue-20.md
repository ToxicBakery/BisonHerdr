# Plan: SSH defects from issue #20

This plan reviews each item in [issue #20](https://github.com/ToxicBakery/BisonHerdr/issues/20)
against `main` at `83f821f`. It gives a verdict for each item, the fix, and the test.
The last section gives the order of the pull requests.

## Summary of verdicts

| # | Item | Verdict |
|---|------|---------|
| 1 | No host-key check | Confirmed |
| 2 | Plain-text passwords on disk | Confirmed |
| 3 | Software key fallback runs on a device | Confirmed |
| 4 | Tailscale and `.local` hosts get a simulated session | Confirmed |
| 5 | Hardware security keys do not work | Confirmed |
| 6 | Biometric keys cannot sign | Confirmed |
| 7 | A sign can make a new key | Confirmed |
| 8 | Keyboard-interactive cannot answer most prompts | Confirmed |
| 9 | ProxyJump connects to the bastion only | Confirmed |
| 10 | No reconnect | Confirmed, with one correction |
| 11 | A network change has no effect on SSH | Confirmed |
| 12 | Mosh never starts on a real host | Confirmed, blocked by item 13 |
| 13 | The Rust core does not ship | Confirmed, needs a decision |
| 14 | The service never stops | Confirmed |
| 15 | Notification permission, actions, service type | Confirmed, with one correction |
| 16 | CI does not test a real SSH connection | Confirmed |

No item is dismissed. Two items have a correction:

- **Item 10.** The app has an implicit retry. The poll loop in `startLivePolling`
  calls `refreshSnapshotAndSurface` every 400 ms, and that call reaches
  `getOrOpenSession`, which opens a new session when the old session is dead.
  The retry has no backoff, no limit, and no status change. The UI shows "Healthy"
  while each poll makes a full SSH connection attempt. The fix below replaces this
  retry with an explicit reconnect loop.
- **Item 15.** The manifest declares the `NFC` permission, so the `connectedDevice`
  service type passes manifest validation. The type is still wrong, because the
  service does not talk to an external device. The fix keeps only `specialUse`.

## Resolved decisions

Three items needed a decision. Each one got a research pass against the code, the
Android documentation, and the libraries. The results are in the items below.

| Decision | Result | Reason |
|----------|--------|--------|
| Backups (item 2) | Keep backups on. Add `dataExtractionRules` that include only the non-secret files. Write secrets to `noBackupFilesDir`. | `allowBackup="false"` does not stop device-to-device transfer on some devices. A keystore key never leaves the device, so a restored secret can never decrypt. The machine list and the known hosts are useful after a restore. |
| Hardware keys (item 5) | Hide the option now. Open one issue for FIDO2 `sk-*` keys with yubikit-android. | Real support is five to seven engineer-days, needs a physical YubiKey, and blocks no other fix. Only yubikit can sign for the `ssh:` relying party. Platform passkey APIs cannot. |
| Rust core (item 13) | Do not build it. Delete the prototype crates and the vendored `libghostty-vt`. Keep `rust/herdr-protocol` as a tested reference crate. Correct the documents. | The Mosh code is a codec, not a transport, and a real `mosh-server` rejects its packets. A working Mosh path is about 29 engineer-days. The SSH path with items 10 and 11 meets both mission goals. |

## Security

### 1. Host-key check

**Evidence.** `RealHerdrSshClient.getOrOpenSession` sets `StrictHostKeyChecking=no`
(`RealHerdrSshClient.kt:1414`). No class implements `HostKeyRepository`.

**Fix.**

1. Add a `KnownHostsStore` in `:core:store`. It keeps one entry per
   `host:port`: key type, SHA-256 fingerprint, and the full public key.
   Keep the file next to `machines.json`.
2. Implement a JSch `HostKeyRepository` in `:platform:android:ssh` on top of the
   store. Set `StrictHostKeyChecking=ask` and give the repository to the `JSch`
   instance.
3. On an unknown host, stop the connection and send a
   `SshRequest.PromptHostKey` with the fingerprint to the UI. The user accepts or
   rejects. On accept, save the key and connect again.
4. On a changed host key, stop the connection and show an error that names the old
   and the new fingerprint. Give the user a way to remove the old entry.
5. Apply the check to the bastion session and to the target session (item 9).
6. Show the known hosts in the machine settings, with a delete action.

**Tests.** With the in-process server from item 16: first connection prompts and
then saves, second connection does not prompt, a changed key stops the connection.

### 2. Password storage

**Evidence.** `ConfigureMachineViewModel` writes the typed text into
`encryptedPasswordRef` (`ConfigureMachineViewModel.kt:208-209`). `MachineStore`
writes the model to `machines.json`. `getOrOpenSession` sends the value to
`session.setPassword` with no decryption (`RealHerdrSshClient.kt:1410-1411`).
`AndroidManifest.xml:18` sets `allowBackup="true"` with no `dataExtractionRules`.
`StoreIOProviderAndroid` writes every store file into `filesDir`
(`StoreIOProviderAndroid.kt:19-24`), and Auto Backup includes that directory. A
quarantine copy `machines.json.corrupt.<epoch>` can also hold a plain-text password.

**Decision on backups.** Keep backups on, and let the rules decide what goes out.

- `allowBackup="false"` stops the cloud backup, but on some devices it does not
  stop the device-to-device transfer
  ([Android 12 behavior changes](https://developer.android.com/about/versions/12/behavior-changes-12)).
  The flag alone does not protect a secret.
- An AndroidKeyStore key never leaves the device
  ([Keystore](https://developer.android.com/privacy-and-security/keystore)). A
  restored ciphertext can never decrypt, so a backup of the ciphertext has no use.
- The machine list, the known hosts, and the settings hold no secret, and they are
  useful after a restore. Keep them in the backup.
- `androidx.security:security-crypto` is deprecated since 1.1.0-alpha07 in favor of
  the platform keystore APIs
  ([release notes](https://developer.android.com/jetpack/androidx/releases/security)).
  The plan uses `KeyGenParameterSpec` and `Cipher` directly.

**Fix.**

1. Add a `SecretStore` interface in `:core:ssh` with `put(ref, secret)`,
   `get(ref)`, and `remove(ref)`. `get` returns `null` for a missing file and for
   an `AEADBadTagException`.
2. Implement `AndroidKeystoreSecretStore` in `:platform:android:ssh`. The key:
   alias `bison_secret_store_v1`, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`,
   `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, key size 256,
   `setRandomizedEncryptionRequired(true)`. Do not set
   `setUserAuthenticationRequired`, `setUnlockedDeviceRequired`, or StrongBox: the
   service reconnects while the screen is locked, and `setUnlockedDeviceRequired`
   has known bugs on Android 12 to 14.
3. Encrypt with `Cipher.getInstance("AES/GCM/NoPadding")`. Let the keystore make
   the 12-byte IV. Pass the reference string as additional authenticated data.
   Write `iv || ciphertext` to one file per reference in
   `context.noBackupFilesDir/secrets/`. Add a second `StoreIOProvider` binding for
   that directory.
4. Implement `InMemorySecretStore` for JVM tests and for the fake server, with a
   `javax.crypto` AES key and the same file format.
5. `ConfigureMachineViewModel` stores the password through `SecretStore` and
   writes `secret:<ref>` into `encryptedPasswordRef`. On edit, the field shows a
   placeholder, not the password.
6. `RealHerdrSshClient` resolves the reference through `SecretStore` before
   `setPassword`. Clear the byte array after `connect`.
7. Add a one-time migration: on load, if `encryptedPasswordRef` has no `secret:`
   prefix, move the value into the store and rewrite the reference. Delete the
   `*.corrupt.*` files in the same step.
8. Set `android:dataExtractionRules="@xml/data_extraction_rules"` in the manifest.
   Keep `allowBackup="true"`. Add `res/xml/data_extraction_rules.xml`:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <data-extraction-rules>
       <cloud-backup disableIfNoEncryptionCapabilities="true">
           <include domain="file" path="machines.json"/>
           <include domain="file" path="settings.json"/>
           <include domain="file" path="known_hosts.json"/>
       </cloud-backup>
       <device-transfer>
           <include domain="file" path="machines.json"/>
           <include domain="file" path="settings.json"/>
           <include domain="file" path="known_hosts.json"/>
       </device-transfer>
   </data-extraction-rules>
   ```

   An include list keeps `*.tmp` and `*.corrupt.*` out. The `secrets/` directory is
   under `noBackupFilesDir`, which Auto Backup never reads.
9. Handle a restore. Write a marker file `device.id` into `noBackupFilesDir` on
   first start. On load, when `machines.json` exists and the marker is absent, set
   a `restoredFromBackup` flag and write the marker.
10. Add a transient `credentialState` to the machine list state: `Ready`,
    `PasswordMissing`, or `KeyMissing`. A `CredentialValidator` in
    `:platform:android:ssh` computes it after load from `SecretStore.get` and
    `keyManager.hasKey(alias)`. The machine card shows "Needs sign-in". For a
    missing password, the connect path opens the password prompt from item 8 and
    stores the new secret. For a missing key, show the item 7 message and a
    "Make a new key" action.

**Tests.** Round trip through the store, `get` of an unknown reference returns
`null`, migration of an old `machines.json`, the restore marker sets the flag,
the validator marks a machine with no secret, and a password login against the
in-process server.

### 3. Software key fallback on a device

**Evidence.** `generateKeyPair` catches every AndroidKeyStore error and makes a
JCA key in `fallbackKeyPairs` (`AndroidKeystoreKeyManager.kt:252-270`). Nothing
limits this path to the JVM.

**Fix.**

1. Add a constructor parameter `allowSoftwareKeys: Boolean = false` to
   `AndroidKeystoreKeyManager`. The JVM tests pass `true`. `SshModule` passes
   nothing.
2. When `allowSoftwareKeys` is `false`, rethrow the AndroidKeyStore error with a
   message that names the cause. The two common causes are: the device has no
   secure lock screen, and the API level does not support Ed25519.
3. Show the error in the configure screen next to the key algorithm selector.

**Tests.** A unit test that constructs the manager with the flag `false` and a
keystore stub that fails. The call must throw.

## Real hosts that the app treats as fake

### 4. Simulated path in the main source set

**Evidence.** `isSimulatedHost` returns `true` for `*.ts.net`, `100.64.*`,
`*.local`, `*.example.com`, `*.invalid`, and an empty host
(`RealHerdrSshClient.kt:218-226`). `shouldUseRealSsh` also returns `false` when
`context` is null or when a test hook is set (`SshTransportManager.kt:97-107`).
`authenticateSshSession`, `negotiateBackbone`, and the other `if (shouldUseRealSsh)`
branches each carry a full simulated implementation.

**Fix.**

1. Extract an `SshBackend` interface from `SshTransportManager`: `authenticate`,
   `negotiateBackbone`, `openSurface`, `sendInput`, `disconnect`, and the other
   calls that today branch on `shouldUseRealSsh`.
2. Move the real branches into `RealSshBackend`, which wraps `RealHerdrSshClient`.
3. Move the simulated branches into `SimulatedSshBackend` in `:core:fake-server`.
   Keep the test hooks (`moshBootstrapBannerProvider` and the others) on that class
   only.
4. `SshTransportManager` takes the backend by constructor. The debug `DebugModule`
   binds a selector that uses `SimulatedSshBackend` only for machines that the
   fake server made. The release `DebugModule` binds `RealSshBackend` only.
5. Delete `isSimulatedHost` and the `context == null` rule.
6. Change the host placeholder in `ConfigureMachineScreen.kt:215` only if the
   example confuses users. A `.ts.net` host is now a real host.

**Tests.** Update `ConnectionPathsE2ETest` and `SshPlatformTest` to construct
`SimulatedSshBackend` directly. Add a test that a `.ts.net` host resolves to
`RealSshBackend` in the release bindings.

## Authentication

### 5. Hardware security keys

**Evidence.** `getOrOpenSession` adds no identity for `HardwareSecurityKey`
(`RealHerdrSshClient.kt:1402`). `YubiKeyFido2Manager` signs with keys from
`KeyPairGenerator` (`YubiKeyFido2Manager.kt:27-36`), and
`isHardwareKeyAvailable()` returns `true`. `SshTransportManager.kt:565-578` signs
a local string, not SSH data.

**Decision.** Hide the option now. Real support is a separate issue.

- Real FIDO2 support is five to seven engineer-days. PIV support is three to four
  days, and few users make PIV SSH keys. Both need a physical YubiKey, which CI
  cannot drive. Neither blocks another fix in this plan.
- Only [yubikit-android](https://github.com/Yubico/yubikit-android) (Apache-2.0,
  version 3.2.1, `minSdk` 21, USB and NFC) can sign for the `ssh:` relying party.
  Its `Ctap2Session.getAssertions` takes any `rpId` string. The Google FIDO2 API
  and Credential Manager need a domain `rpId` with an `assetlinks.json` check, so
  they cannot make an OpenSSH `sk` assertion
  ([Google FIDO2 for native apps](https://developers.google.com/identity/fido/android/native-apps)).
- JSch 2.28.x has no `sk-*` support, but a custom `Identity` works. The signature
  blob from `getSignature(data, alg)` goes into the packet as is. The app must add
  the `sk-*` names to `PubkeyAcceptedAlgorithms` and register a placeholder
  `Signature` class, because `Session.checkSignatures` marks a name with no class
  as unavailable.
- About 80 percent of the current code stays for the real implementation: the
  model, the `HardwareKeySigner` contract, `PromptHardwareKeyTouch`, the prompt
  sheet, and the `AndroidKeystoreJschIdentity` pattern. Only the signer is new.
  `buildOpenSshSkSignatureBlob` (`YubiKeyFido2Manager.kt:86-130`) has the correct
  wire format and moves into the new identity later.

**Fix.**

1. Remove `AuthType.HardwareSecurityKey` from the choices in
   `ConfigureMachineScreen.kt:291`. Keep the enum value and the
   `SshAuthMethod.HardwareSecurityKey` model, so that old `machines.json` files
   still load.
2. When a loaded machine has this method, show "Not supported in this version" and
   block the connect action.
3. Delete `YubiKeyFido2Manager.kt`, its binding in `SshModule.kt:28-36`, and the
   `HardwareSecurityKey` branch in `SshTransportManager.kt:565-578`. Keep
   `HardwareKeySigner`, `PromptHardwareKeyTouch`, and
   `HardwareSecurityKeyPromptSheet`.
4. Remove the `NFC` permission and the `nfc` and `usb.host` features from the
   manifest (`AndroidManifest.xml:10-14`). The real implementation adds them back.
5. Update `SshPlatformTest.kt:122-129,275` and
   `ConnectionPathsE2ETest.kt:274-350` to remove the YubiKey cases.

**Tests.** A view-model test that the choice list has two entries.

**New issue "FIDO2 security keys".** The issue must specify:

- Library: `com.yubico.yubikit:android`, `core`, and `fido` 3.2.1. No `piv`.
- Key types: `sk-ssh-ed25519@openssh.com` first, `sk-ecdsa-sha2-nistp256@openssh.com`
  second. Ed25519 gives a raw 64-byte signature. P-256 needs DER to `mpint r, s`.
- Model: add `credentialId`, `publicKey`, and `residentKey` to
  `HardwareSecurityKey`. These are public data and go into `machines.json`.
- UI: an "Enroll key" action on the configure screen. It runs `makeCredential`
  with `rp = {id: "ssh:"}` under the touch sheet, then shows the `.pub` line with
  the copy action from PR #16. On connect, a `HardwareKeyJschIdentity` calls
  `getAssertions("ssh:", SHA256(data), allowList=[credentialId])` and returns
  `string sig, byte flags, uint32 counter` from the authenticator data.
- JSch: the `PubkeyAcceptedAlgorithms` entries and the placeholder `Signature`
  class.
- Transport: `YubiKitManager.startUsbDiscovery` and `startNfcDiscovery` from
  `MainActivity`, with the permission and the two features restored.
- Tests: a unit test with a fake `FidoConnection` for the wire format, and a
  manual test card with a YubiKey 5 over USB-C and NFC against the item 16 server.

### 6. Biometric keys

**Evidence.** `generateInAndroidKeystore` calls
`setUserAuthenticationRequired(requireBiometric)` with no validity duration
(`AndroidKeystoreKeyManager.kt:288,300,311`). JSch signs on a worker thread, and
no code shows a `BiometricPrompt`.

**Fix.** Use a time-bound authentication, so that the sign can run on the JSch
thread without a `CryptoObject`.

1. Call `setUserAuthenticationParameters(30, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`.
   The app has `minSdk = 31`, so no older branch is necessary.
2. Before `session.connect`, when `requireBiometric` is `true`, send
   `SshRequest.PromptBiometric` to the UI. `MainActivity` shows a `BiometricPrompt`
   from `androidx.biometric` and reports the result back through a
   `CompletableDeferred`. Reuse the pattern of `HardwareSecurityKeyPromptSheet`.
3. When `signSshChallenge` throws `UserNotAuthenticatedException`, map it to a
   clear error. Do not retry inside the sign.
4. Keys that exist already keep the old parameters. Show a "regenerate key"
   action in the configure screen, with a warning that the server needs the new
   public key.

**Tests.** A device test in `androidTest` that a key with the new parameters
signs after a successful prompt. A JVM test cannot cover this.

### 7. A sign makes a new key

**Evidence.** `signSshChallenge` calls `generateKeyPair(..., requireBiometric = false)`
when the alias has no usable key (`AndroidKeystoreKeyManager.kt:116-120`).

**Fix.**

1. Remove the generate call from `signSshChallenge`. Throw
   `MissingKeyException(alias)` instead.
2. `getOrCreatePublicKey` stays the only path that makes a key.
3. `AndroidKeystoreJschIdentity` lets the exception reach `getOrOpenSession`,
   which records "The key <alias> does not exist. Open the machine settings and
   copy the public key." in the debug log and in the connect error.

**Tests.** A unit test that a sign with an unknown alias throws and makes no key.

### 8. Keyboard-interactive prompts

**Evidence.** `PreferredAuthentications` lists `keyboard-interactive`
(`RealHerdrSshClient.kt:1389-1402`), and the session has no `UserInfo`. JSch
answers only a single prompt that contains `password:`.

**Fix.**

1. Implement `UIKeyboardInteractive` and `UserInfo` in
   `:platform:android:ssh`. For each prompt set, send
   `SshRequest.PromptKeyboardInteractive(name, instruction, prompts, echo)` to the
   UI and wait for the answers.
2. When the machine has a stored password, and the set has one prompt that
   contains `password`, answer it from the `SecretStore` (item 2) without a prompt.
3. Add a small Compose sheet for the prompt. Reuse the hardware key sheet layout.
4. Route the host-key prompt from item 1 through the same `UserInfo`.

**Tests.** With the in-process server: configure a keyboard-interactive
authenticator with two prompts, and assert that the UI receives both.

## Jump hosts

### 9. ProxyJump

**Evidence.** `resolveRoute` sets the entry fields to the bastion
(`ProxyJumpTunnel.kt:69-92`). `getOrOpenSession` opens the session to
`route.entryHost` (`RealHerdrSshClient.kt:1404-1408`). No code uses
`directTcpIpPayload`.

**Fix.** Use JSch local port forwarding. It is the simplest path with JSch and it
needs no custom `Proxy`.

1. In `getOrOpenSession`, when `route.viaBastion` is `true`, open a bastion
   session first with the bastion user and the same identity. Apply the host-key
   check from item 1.
2. Call `bastion.setPortForwardingL("127.0.0.1", 0, route.targetHost, route.targetPort)`.
   The call returns the bound local port.
3. Open the target session to `127.0.0.1:<port>` with the target user. Register
   the host key under `route.targetHost:route.targetPort`, not under the local
   address.
4. Keep the bastion session in a second map. `disconnect` closes the target and
   then the bastion. When the bastion dies, mark the target dead too.
5. Delete `directTcpIpPayload` and `buildDirectTcpIpChannelPayload`. Update the
   tests that assert on them.

**Tests.** With two in-process servers: a login via the bastion runs `hostname`
on the target. A failed bastion login gives an error that names the bastion.

## Connection resilience

### 10. Reconnect

**Evidence.** See the correction above. The poll loop retries every 400 ms with no
backoff and no status (`RealHerdrSshClient.kt:296-311`, `1371-1383`).

**Fix.**

1. Add a `SessionSupervisor` per machine in `RealHerdrSshClient`. It owns the
   session and runs a reconnect loop: delays of 1, 2, 4, 8, 16, and 30 s, each with
   random jitter of up to 25 percent, with no upper count while the machine stays in
   the connected set.
2. `getOrOpenSession` no longer opens a session on demand. When the session is
   dead, it suspends until the supervisor has a new one, or fails after one loop
   step when the caller is a user input.
3. The supervisor sets `RoamingStatus.RoamingReconnect` on the first failure and
   `Healthy` after a successful reconnect. The poll loop pauses while the status is
   `RoamingReconnect`.
4. After a reconnect, send `client_shell.surface.set { active: true }` again and
   resync the surface. Replay the queued prompts through `flushOfflinePrompts`.

**Tests.** With the in-process server: stop the server, assert the status
changes, start the server, assert the session comes back and a command runs.

### 11. Network changes

**Evidence.** `onNetworkAvailable` only sets two statuses and writes a log line
(`SshTransportManager.kt:458-488`). The service has no `onLost`
(`BisonSessionService.kt:70-92`).

**Fix.**

1. In `onNetworkAvailable`, for each machine on the SSH backbone, run a liveness
   check with a 3 s timeout: `session.sendKeepAliveMsg()` and then one `exec true`.
   On failure, ask the supervisor from item 10 to reconnect now, with no delay.
2. Add `onLost` to the callback in `BisonSessionService`. It calls
   `sshTransportManager.onNetworkLost()`, which sets `RoamingReconnect` and closes
   the sockets, so that the next open does not wait for the TCP timeout.
3. Debounce `onAvailable` and `onLinkPropertiesChanged` to one check per second
   per machine.
4. Fix the KDoc so that it describes what the function does.

**Tests.** A unit test with a fake backend that counts liveness checks and
reconnect requests.

## Transport

### 12. Mosh on the real path

**Evidence.** `negotiateBackbone` returns `ActiveBackbone.MoshUdp` with fixed
`rttMs = 9` when the host has `mosh-server`, and never starts the engine
(`SshTransportManager.kt:582-607`). `MoshUdpEngine` needs the native library,
which item 13 does not build. The real path cannot run Mosh today.

**Fix.** Report the truth until item 13 is resolved.

1. On the real path, always return `ActiveBackbone.SshEndpoint`. Keep
   `checkMoshServerAvailable`, and put the result into `fallbackReason`
   ("mosh-server found, Mosh is not available in this version").
2. Measure the RTT: time the `exec` round trip in `refreshSnapshotAndSurface`,
   keep an exponential moving average, and update the backbone `rttMs` once per
   second when it changes.
3. Remove the fixed values 9 and 12.
4. Mosh stays out of this version. The new issue from item 13 holds the
   acceptance list for a real Mosh path.

**Tests.** A test that the RTT on the backbone changes after measured round trips.

### 13. Rust core

**Evidence.** `jni_bridge.rs` exports nine `HerdrNativeBridge` functions
(`jni_bridge.rs:131-292`), and no Kotlin class has that name. Gradle has no Cargo
or NDK step. CI has no `cargo` or `zig` step. `isNativeLibraryLoaded` is always
`false` (`MoshUdpEngine.kt:434-439`). The `rust/` tree has three commits, all on
2026-10-20.

The research pass found more than the issue reports:

- `mosh_ssp.rs` (704 lines) is a codec, not a transport. Nothing in the crate
  opens a UDP socket. It has no fragment header, no `UserMessage` or `HostMessage`
  protobuf, no sent-state list, and no retransmit timer. A real `mosh-server`
  rejects its packets. The AES-128-OCB3 code is correct against the RFC 7253
  test vectors.
- The workspace does not build. `rust/ghostty-vt/build.rs` runs `zig build` on
  `vendor/libghostty-vt` (468 MB), which needs Zig 0.16.0. The machine has Zig
  0.17.0. `herdr-mobile-core` depends on `ghostty-vt`, so it cannot build or test.
  `cargo test -p herdr-protocol` passes 50 tests with zero clippy warnings.
- Seven of the nine JNI exports duplicate Kotlin code in `:core:protocol`
  (`MoshBootstrapConfig.kt:80,97`, `PaneLayoutDerivation.kt:62,118,124`,
  `EndpointAndInputModels.kt:16`). Only `nativeSynthesizeMoshFrameJson` and the
  SSP codec have no Kotlin equivalent.
- `russh`, `ssh-key`, `tokio`, and `async-trait` serve only a dead handler in
  `ssh_client.rs`. Nothing ships `vendor/libghostty-vt`.
- `MoshUdpEngine.transmitSspDatagram` (`MoshUdpEngine.kt:303-317`) sends plain
  `seq || 0x01` frames with no encryption and never reads a reply. `startSession`
  returns `true` after one send, so the handshake in
  `SshTransportManager.kt:626-640` verifies nothing.
- The NDK is installed, `cargo-ndk` and the Android rustup targets are not.

**Effort for a working Mosh path.** About 29 engineer-days: toolchain and CI (3),
`HerdrNativeBridge` (1), a wire-compatible SSP with fragments, protobuf messages,
state list, and timers (10), the UDP socket, event loop, and roaming (5), the
VT frame path through ghostty-vt (5), and integration with tests (5). A pure-Kotlin
SSP client saves the toolchain step but still needs a VT emulator, which the app
does not have. The Herdr endpoint sends rendered cells, so the SSH path needs none.

**Decision.** Do not build it. The SSH path meets both mission goals in
`CLAUDE.md` section 2: the design document itself names the surface lease and
`NetworkCallback` fast-roam as the answer to roaming
(`01-core-app-design.md:14`), and items 10 and 11 complete that path. Mosh adds
only zero-delay roaming and predictive echo. Delete the prototype crates and the
vendored library: the git history keeps them, they do not build with the installed
Zig, and the checkout drops 468 MB. Keep `rust/herdr-protocol` as a tested
reference for the Herdr wire types.

**Fix.** Branch `chore/rust-core-status`.

1. Delete `rust/herdr-mobile-core`, `rust/ghostty-vt`, and
   `vendor/libghostty-vt`. Remove them from `rust/Cargo.toml`. Remove `russh`,
   `ssh-key`, `tokio`, and `async-trait` from the workspace. Run `cargo update`
   so that `Cargo.lock` drops them.
2. Add a `rust/README.md` note: the crate is a reference for the Herdr wire types,
   and Gradle does not build it.
3. Add a CI job to `validation.yml` that runs `cargo test -p herdr-protocol` and
   `cargo clippy -p herdr-protocol -- -D warnings`.
4. In `MoshUdpEngine.kt`, delete the `DatagramChannel` send path
   (lines 92-96 and 303-317) and `isNativeLibraryLoaded`. `startSession` returns
   `false` with the log line "Mosh is not available in this version". Update the
   KDoc at lines 21-33.
5. Correct `README.md`: line 18 (Mosh backbone) becomes "planned"; line 29
   (`libghostty-vt` over JNI) describes the Compose canvas that renders Herdr
   `PaneSurfaceFrame` cells; lines 39 and 49 name JSch and `:core:protocol`, and
   call `rust/herdr-protocol` a reference crate.
6. Correct `docs/plans/01-core-app-design.md`: lines 394, 476, and 477 (replace
   `rust/herdr-mobile-core` with the Kotlin protocol module or mark as planned);
   lines 597, 599, 622, and 630 (module tree); lines 962 to 970 (`russh` JNI
   claims); line 985 (Phase 2 status: not started); line 998 (limit the `cargo`
   definition of done to `herdr-protocol`). Keep line 14, it is true.
7. Correct `CLAUDE.md` section 2: mark "Mosh UDP SSP zero-traffic idle suspend"
   and "120Hz `libghostty-vt` JNI rendering" as planned.
8. Open the issue "Mosh UDP backbone" with the six effort steps above and the
   wire-compatibility gaps: fragment header, `UserMessage` and `HostMessage`,
   sent-state list, timers, socket, and VT emulator. Link the last commit that has
   the deleted crates.

## Foreground service

### 14. Service lifecycle

**Evidence.** `MainActivity.startSessionService` runs on every start
(`MainActivity.kt:111-112`). The service returns `START_STICKY` and never calls
`stopSelf` (`BisonSessionService.kt:110-113`).

**Fix.**

1. The service observes `sshStateManager.connectedMachines`. When the set has been
   empty for 30 s, it calls `stopForeground(STOP_FOREGROUND_REMOVE)` and
   `stopSelf()`.
2. `MainActivity` starts the service on launch, as today, because the service is
   the dispatcher for `SshRequestManager`. The service stops itself only when no
   machine is connected and the activity is stopped. Use a `started` flag from the
   activity lifecycle, sent through `SshRequest.SetSurfaceLease`.
3. Return `START_NOT_STICKY`. A restart with no session has no work.
4. The `SharedFlow` in `SshRequestManager` has a buffer of 64 and no replay. When
   the service is not running, a request is lost. Change the activity so that it
   starts the service and then emits, and give the flow `replay = 1` for
   `Connect` requests.

**Tests.** A Robolectric test is not in the project. Add a unit test for the
30 s timer logic in a small `ServiceStopPolicy` class.

### 15. Notification permission, actions, and service type

**Evidence.** The manifest declares `POST_NOTIFICATIONS`, and no code requests it
at runtime. The notification has only agent actions
(`BisonSessionService.kt:433-435`). The manifest sets
`connectedDevice|specialUse` (`AndroidManifest.xml:42`).

**Fix.**

1. In `MainActivity`, on API 33 and later, request `POST_NOTIFICATIONS` with
   `ActivityResultContracts.RequestPermission` before the first connect. Show a
   short rationale when the user denied it once.
2. Add `ACTION_DISCONNECT_ALL` to the ongoing notification. It emits one
   `SshRequest.Disconnect` for each machine in `connectedMachines`.
3. Set `android:foregroundServiceType="specialUse"` only. Remove
   `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Keep the subtype text, and shorten it to
   one clause: "Keeps SSH terminal sessions open in the background".
4. Remove the `NFC` permission and the `nfc` and `usb.host` features with item 5.

**Tests.** A manifest assertion in the existing `app` unit tests, if present, or a
review step in the pull request.

## Tests

### 16. Real SSH server in CI

**Evidence.** `ConnectionPathsE2ETest` and `SshPlatformTest` pass
`context = null` (`ConnectionPathsE2ETest.kt:47-48`), so they only test the
simulated path. `validation.yml` runs no device test and no `cargo` step.

**Fix.**

1. Add `org.apache.sshd:sshd-core` (Apache-2.0) as `testImplementation` in
   `:platform:android:ssh`.
2. Add a `TestSshServer` fixture: random port, one user with a password, one user
   with an `authorized_keys` entry from the JVM key fallback (item 3, flag `true`),
   a host key per server instance, a `keyboard-interactive` authenticator, and a
   command factory that runs `sh -c`.
3. Add `RealSshClientTest` with the tests listed under items 1, 2, 8, 9, and 10.
   Each test opens `RealHerdrSshClient` against the fixture with
   `RealSshBackend`.
4. `RealHerdrSshClient` has no Android import, so it runs on the JVM as is.
   `SshTransportManager` imports `android.content.Context` only for the
   `shouldUseRealSsh` rule, which item 4 removes.
5. Keep `AgyCursorPlacementE2ETest` as a manual device test. Add a note in
   `scripts/e2e/agy-cursor-e2e.sh` that CI does not run it.
6. The `cargo` CI job for `herdr-protocol` comes with item 13.

## Order of pull requests

Each pull request comes from its own worktree and branch, as `CLAUDE.md` requires.
The order differs from the issue in one point: items 4 and 16 come first, so that
every later fix has a test against a real server.

| Order | Branch | Items |
|-------|--------|-------|
| 1 | `refactor/ssh-backend-split` | 4, 16 |
| 2 | `fix/ssh-host-keys` | 1 |
| 3 | `fix/ssh-password-secrets` | 2 |
| 4 | `fix/keystore-no-software-keys` | 3, 7 |
| 5 | `feat/ssh-proxyjump` | 9 |
| 6 | `fix/ssh-reconnect-roaming` | 10, 11 |
| 7 | `fix/ssh-backbone-report` | 12 |
| 8 | `fix/biometric-keys` | 6 |
| 9 | `feat/keyboard-interactive` | 8 |
| 10 | `fix/hide-hardware-key` | 5, and the `NFC` part of 15 |
| 11 | `fix/session-service-lifecycle` | 14, 15 |
| 12 | `chore/rust-core-status` | 13 |

Open two issues for the work that this plan defers: "FIDO2 security keys"
(item 5) and "Mosh UDP backbone" (item 13). Both items above give the content.
