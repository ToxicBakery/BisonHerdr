# Manual test card: FIDO2 security keys

This card tests FIDO2 security keys (issue #23) with a physical YubiKey 5 over USB-C
and over NFC, against a real OpenSSH server. CI cannot use a physical key. The unit
tests in `:platform:android:ssh` use a software key (`FakeSecurityKey`) and an
in-process server (`TestSshServer`, Apache MINA SSHD), but they do not prove the USB,
NFC, and touch behavior of a real key.

The feature flag `FeatureFlags.hardwareSecurityKeys` is off in each build type. Keep
it off in a release until all cards on this page pass.

## Equipment

- An Android tablet or phone with Android 12 (API 31) or later, NFC, and a USB-C port
  with USB host support.
- A YubiKey 5 series key with USB-C and NFC, for example a YubiKey 5C NFC.
  - Ed25519 credentials need firmware 5.2.3 or later.
  - Use a key with no FIDO2 PIN. This version cannot send a PIN. A key with a PIN
    can refuse the enrollment with "The security key needs its PIN". Record this
    result if it occurs.
- A second FIDO2 key for the wrong key test (card D, step 4). Any FIDO2 key is
  satisfactory.
- An SSH server with OpenSSH 8.2 or later and Herdr, that the device can reach over the
  network. Use the host of the other manual tests. OpenSSH 8.2 and later accept
  `sk-ssh-ed25519@openssh.com` and `sk-ecdsa-sha2-nistp256@openssh.com` by default.
- A computer with this repository, the Android SDK, and `adb`.

## Step 0: Install a build with the flag on

1. Connect the device to the computer with `adb`.
2. Run this command from the root of the repository:

   ```
   ./gradlew :app:android:installDebug -Pbison.hardwareSecurityKeys=true
   ```

   The Gradle property sets `BuildConfig.HARDWARE_SECURITY_KEYS`. Do not put the
   property in a `gradle.properties` file, because each later build then gets the flag.
3. Open the app and tap the button that adds a host.
4. Make sure that "SSH Authentication Strategy" shows the "FIDO2 Security Key" choice.
   If the choice does not show, the build does not have the flag. Do step 2 again.

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
| D4 | Connect the second FIDO2 key. Tap "Connect Host" and touch the second key. | The error on the connecting screen contains "This security key does not have the credential of this machine". |
| D5 | Remove the line of the key from `authorized_keys`. Tap "Connect Host". | The touch sheet does not show, because the server refuses the key before the app asks for a signature. The login fails, or the server asks for a password. If the server asks for a password, tap "Cancel". |
| D6 | Add the line again and connect. Put the app in the background. Turn airplane mode on, and then off. | If the session closes, the reconnect fails with "The app is in the background, so it cannot use the security key". The app does not ask for a touch in the background. |
| D7 | Open the app. Tap "Connect Host" and touch the key. | The terminal opens. |
| D8 | In the editor, select the other key type. | The public key line goes away. "Save & Connect" is not available until you enroll the key again. |

## Card E: Flag off

1. Install a build with no flag:

   ```
   ./gradlew :app:android:installDebug
   ```

2. Open the editor of the machine of card A.

| Step | Action | Expected result |
| --- | --- | --- |
| E1 | Look at "SSH Authentication Strategy". | The screen shows "Hardware security key: Not supported in this version". It does not show "Enroll key". |
| E2 | Open "Add Herdr Host". | The choices are only "Android Keystore Key" and "Password / Passphrase". |
| E3 | Go to the machine list. | The card of the machine shows "Not supported in this version". "Connect Host" is not available. |
| E4 | Connect the YubiKey to the USB-C port. | The app does not ask for the USB permission. |
| E5 | Hold the YubiKey against the NFC area while the app is open. | The app does not take the tag. Android handles the tag as before, for example it opens the Yubico OTP link. |

## Result record

Write one row for each card. Add the record to the pull request that turns on the flag.

| Card | Device and Android version | Key and firmware | Server OpenSSH version | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| A | | | | | |
| B | | | | | |
| C | | | | | |
| D | | | | | |
| E | | | | | |
