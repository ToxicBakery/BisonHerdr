# Manual test card: FIDO2 security keys

This card tests FIDO2 security keys (issue #23) with a physical YubiKey 5 over USB-C
and over NFC, against a real OpenSSH server. CI cannot use a physical key. The unit
tests in `:platform:android:ssh` use a software key (`FakeSecurityKey`) and an
in-process server (`TestSshServer`, Apache MINA SSHD), but they do not prove the USB,
NFC, and touch behavior of a real key.

The feature flag `FeatureFlags.hardwareSecurityKeys` is off in each build type. Keep
it off in a release until all cards on this page pass. A release build with
`-Pbison.hardwareSecurityKeys=true` stops with an error (`checkReleaseFeatureFlags` in
`app/android/build.gradle.kts`). The pull request that turns on the flag removes this
check.

Over USB, the app accepts only YubiKeys (the Yubico vendor ID). Over NFC, other FIDO2
keys can work, but nobody tested them.

## Equipment

- An Android tablet or phone with Android 12 (API 31) or later, NFC, and a USB-C port
  with USB host support.
- A YubiKey 5 series key with USB-C and NFC, for example a YubiKey 5C NFC.
  - Ed25519 credentials need firmware 5.2.3 or later.
  - Use a key with no FIDO2 PIN. This version cannot send a PIN. A key with a PIN
    can refuse the enrollment with "The security key needs its PIN", for example a
    YubiKey 5 with firmware before 5.7. Record this result if it occurs.
- A second YubiKey for the wrong key test (card D, step D4).
- An SSH server with OpenSSH 8.2 or later and Herdr, that the device can reach over the
  network. Use the host of the other manual tests. OpenSSH 8.2 and later accept
  `sk-ssh-ed25519@openssh.com` and `sk-ecdsa-sha2-nistp256@openssh.com` by default.
- A second SSH server for the ProxyJump tests (card D, steps D11 and D12). The bastion
  user must have the public key of the YubiKey in `~/.ssh/authorized_keys`.
- A computer with this repository, the Android SDK, and `adb`.

## Step 0: Check the servers and install a build with the flag on

1. On each SSH server, run this command:

   ```
   sudo sshd -T | grep -iE 'pubkeyacceptedalgorithms|pubkeyauthoptions'
   ```

   The `pubkeyacceptedalgorithms` line must show `sk-ssh-ed25519@openssh.com` and
   `sk-ecdsa-sha2-nistp256@openssh.com`. The `pubkeyauthoptions` line must not show
   `verify-required`, because this version cannot send a PIN. If a check fails, change
   `sshd_config` before you start.
2. Connect the device to the computer with `adb`.
3. Run this command from the root of the repository:

   ```
   ./gradlew :app:android:installDebug -Pbison.hardwareSecurityKeys=true
   ```

   The Gradle property sets `BuildConfig.HARDWARE_SECURITY_KEYS`. Do not put the
   property in a `gradle.properties` file, because each later build then gets the flag.
4. Open the app and tap the button that adds a host.
5. Make sure that "SSH Authentication Strategy" shows the "FIDO2 Security Key" choice.
   If the choice does not show, the build does not have the flag. Do step 3 again.

## Card A: USB-C with `sk-ssh-ed25519@openssh.com`

| Step | Action | Expected result |
| --- | --- | --- |
| A1 | Type the host name, the port, and the user of the server. | The fields show the values. |
| A2 | Select "FIDO2 Security Key". | The key type chips, the connection chips, and "Enroll key" show. |
| A3 | Select `sk-ssh-ed25519@openssh.com` and "USB-C and NFC". | The two chips are selected. |
| A4 | Connect the YubiKey to the USB-C port. Tap "Enroll key". | Android asks for the USB permission of the YubiKey. |
| A5 | Allow the USB permission. | The touch sheet shows "Touch the security key, or hold it against the device, to enroll it for this machine." The key flashes. |
| A6 | Touch the key. | The touch sheet closes. The screen shows an `sk-ssh-ed25519@openssh.com AAAA... bison-tablet-security-key` line and "Copy public key". |
| A7 | Tap "Copy public key". Add the line to `~/.ssh/authorized_keys` of the user on the server. | The button shows "Copied". |
| A8 | Tap "Save & Connect". | The connecting screen opens. The touch sheet shows "Touch the security key, or hold it against the device, to log in to this machine." |
| A9 | Touch the key. | The touch sheet closes. The terminal of the host opens. |
| A10 | On the server, read the log of `sshd`, for example with `journalctl -u ssh`. | The log has `Accepted publickey for <user> ... ED25519-SK`. |
| A11 | Go back to the machine list. Disconnect, and then connect again from the card. | The touch sheet shows again. One touch connects the host. |

## Card B: USB-C with `sk-ecdsa-sha2-nistp256@openssh.com`

Do card A again with these changes:

- In step A3, select `sk-ecdsa-sha2-nistp256@openssh.com`.
- In step A6, the line starts with `sk-ecdsa-sha2-nistp256@openssh.com`.
- In step A10, the log has `ECDSA-SK`.

Do steps A8 and A9 three times. Each login must pass. The P-256 signatures have
random values, so a defect in the integer conversion fails only some logins.

## Card C: NFC

Remove the key from the USB-C port before this card. Do card A and card B again with
these changes:

- In step A3, select "NFC".
- In steps A4 and A5, Android does not ask for a USB permission.
- In steps A6 and A9, hold the key against the NFC area of the device until the touch
  sheet closes. Do not touch the key.

## Card D: Errors

Use the machine of card A for each step.

| Step | Action | Expected result |
| --- | --- | --- |
| D1 | Tap "Connect Host". When the touch sheet shows, tap "Cancel". | The touch sheet closes at once. The error on the connecting screen contains "The security key request was cancelled." The app does not stop. |
| D2 | Tap "Connect Host". Do not touch the key for 40 seconds. | The error on the connecting screen contains "The user did not touch the security key before the time limit." |
| D3 | Remove the key. Tap "Connect Host" and wait 60 seconds. | The error on the connecting screen contains "No security key came before the time limit". |
| D4 | Connect the second YubiKey. Tap "Connect Host" and touch the second key. | The error on the connecting screen contains "This security key does not have the credential of this machine". |
| D5 | Remove the line of the key from `authorized_keys`. Tap "Connect Host". | The touch sheet does not show, because the server refuses the key before the app asks for a signature. The login fails, or the server asks for a password. If the server asks for a password, tap "Cancel". |
| D6 | Add the line again and connect. Put the app in the background. Turn airplane mode on, and then off. | If the session closes, the reconnect fails with "The app is in the background, so it cannot use the security key". The app does not ask for a touch in the background. |
| D7 | Open the app. Tap "Connect Host" and touch the key. | The terminal opens. |
| D8 | In the editor, select the other key type. | The public key line goes away. "Save & Connect" is not available until you enroll the key again. Select the first key type again, or enroll again. |
| D9 | Remove the YubiKey from the USB-C port, and connect it again. Android then forgets the USB permission. Tap "Connect Host". When Android asks for the USB permission, deny it. | The permission dialog closes and does not show again during this request. The touch sheet stays. Tap "Cancel" on the touch sheet. The error contains "The security key request was cancelled." |
| D10 | Tap "Connect Host" again. Allow the USB permission and touch the key. | The terminal opens. |
| D11 | In the editor, turn on the ProxyJump bastion with the second SSH server. Tap "Save & Connect". | The touch sheet shows two times: first for the bastion, then for the host. Touch the key each time. The terminal opens. |
| D12 | Remove the key from the USB-C port. Select "NFC" in the editor, and tap "Save & Connect". | The touch sheet shows two times. Hold the key against the NFC area each time, until the sheet closes. The terminal opens. |
| D13 | Select "USB-C and NFC", and turn off the ProxyJump bastion. Disconnect. Tap "Connect Host", and then tap "Cancel" on the connecting screen at once, before the touch sheet shows. | The connect stops. The touch sheet does not show later for this machine, and the key does not flash. If the touch sheet shows before you can tap "Cancel", the network is too fast for this step. Tap "Cancel" on the sheet, and do the step again with a host that is farther away. |

## Card E: Minified build with the flag on

The debug build of step 0 has no R8. This card tests the minified benchmark build,
which uses the release rules of R8 and the debug signature. Thus it tests the classes
that yubikit, slf4j, and JSch load at run time.

1. Install the benchmark build with the flag:

   ```
   ./gradlew :app:android:installBenchmark -Pbison.hardwareSecurityKeys=true
   ```

2. Do steps A1 to A9 of card A, and then steps A1 to A9 of card C for one key type.

| Step | Action | Expected result |
| --- | --- | --- |
| E1 | Do the steps of card A over USB-C. | The results agree with card A. The app does not stop. |
| E2 | Do the steps of card C over NFC. | The results agree with card C. The app does not stop. |
| E3 | Run `adb logcat -d \| grep -iE "ClassNotFound\|NoSuchMethod\|ServiceConfigurationError"`. | The command finds no line for the app. |

## Card F: Flag off

1. Install a build with no flag:

   ```
   ./gradlew :app:android:installDebug
   ```

2. Open the editor of the machine of card A.

| Step | Action | Expected result |
| --- | --- | --- |
| F1 | Look at "SSH Authentication Strategy". | The screen shows "Hardware security key: Not supported in this version". It does not show "Enroll key". |
| F2 | Open "Add Herdr Host". | The choices are only "Android Keystore Key" and "Password / Passphrase". |
| F3 | Go to the machine list. | The card of the machine shows "Not supported in this version". "Connect Host" is not available. |
| F4 | Connect the YubiKey to the USB-C port. | The app does not ask for the USB permission. |
| F5 | Hold the YubiKey against the NFC area while the app is open. | The app does not take the tag. Android handles the tag as before, for example it opens the Yubico OTP link. |
| F6 | On the computer, run `./gradlew :app:android:assembleRelease -Pbison.hardwareSecurityKeys=true`. | The build stops in `checkReleaseFeatureFlags` with "bison.hardwareSecurityKeys=true is only for a debug or benchmark test build". |

## Result record

Write one row for each card. Add the record to the pull request that turns on the flag.

| Card | Device and Android version | Key and firmware | Server OpenSSH version | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| A | | | | | |
| B | | | | | |
| C | | | | | |
| D | | | | | |
| E | | | | | |
| F | | | | | |
