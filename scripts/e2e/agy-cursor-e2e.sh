#!/usr/bin/env bash
# Live end-to-end check of the terminal cursor position against an agy (Antigravity CLI) agent.
#
# The script does these steps:
#   1. Starts an isolated Herdr session and runs `agy` in a new workspace.
#   2. Starts a private sshd on 127.0.0.1. The sshd sets HERDR_SOCKET_PATH to the isolated session.
#   3. Boots a headless emulator and installs the :platform:android:ssh instrumented test APK.
#   4. Runs AgyCursorPlacementE2ETest. The test connects to 10.0.2.2 (the host loopback)
#      through SshTransportManager and compares the frame cursor with the agy prompt caret.
#
# The script does not change the default Herdr session or the system sshd.
# The script stops all processes that it starts. Set BISON_E2E_KEEP=1 to keep them.
#
# Environment:
#   BISON_E2E_AVD       AVD to boot (default: Pixel_Tablet)
#   BISON_E2E_SERIAL    Serial of an emulator that runs already. The script then does not boot an AVD.
#   BISON_E2E_EMU_PORT  Console port for the booted emulator (default: 5580)
#   BISON_E2E_SSH_PORT  Port for the private sshd (default: 2299)
#   BISON_E2E_SESSION   Herdr session name (default: bison-agy-cursor-e2e)
#   BISON_E2E_WORKDIR   Work directory (default: ~/.cache/bison-agy-cursor-e2e)
#   BISON_E2E_KEEP      Set to 1 to keep the session, sshd, and emulator after the run
#   BISON_E2E_SKIP_BUILD Set to 1 to use the APK that is built already
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AVD="${BISON_E2E_AVD:-Pixel_Tablet}"
EMU_PORT="${BISON_E2E_EMU_PORT:-5580}"
SERIAL="${BISON_E2E_SERIAL:-}"
SSH_PORT="${BISON_E2E_SSH_PORT:-2299}"
SESSION="${BISON_E2E_SESSION:-bison-agy-cursor-e2e}"
WORKDIR="${BISON_E2E_WORKDIR:-${XDG_CACHE_HOME:-$HOME/.cache}/bison-agy-cursor-e2e}"
KEEP="${BISON_E2E_KEEP:-0}"
SKIP_BUILD="${BISON_E2E_SKIP_BUILD:-0}"

TEST_PKG="com.toxicbakery.app.bison.platform.ssh.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.toxicbakery.app.bison.ssh.AgyCursorPlacementE2ETest"
LOG_TAG="BisonAgyCursorE2E"
TEST_APK="$REPO_ROOT/platform/android/ssh/build/outputs/apk/androidTest/debug/ssh-debug-androidTest.apk"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
EMULATOR_BIN="$SDK/emulator/emulator"

HERDR_SOCKET="$HOME/.config/herdr/sessions/$SESSION/herdr.sock"
AGY_CWD="$WORKDIR/agy-cwd"
SSHD_DIR="$WORKDIR/sshd"
REPORT="$WORKDIR/report.txt"

SSHD_PID=""
EMU_PID=""
BOOTED_EMULATOR=0

log() { printf '[agy-cursor-e2e] %s\n' "$*" >&2; }
fail() { log "ERROR: $*"; exit 1; }

require() {
    command -v "$1" >/dev/null 2>&1 || fail "Install '$1' and put it on PATH."
}

herdr_s() { HERDR_SOCKET_PATH="$HERDR_SOCKET" herdr "$@"; }

cleanup() {
    local status=$?
    if [[ "$KEEP" == "1" ]]; then
        log "BISON_E2E_KEEP=1: the session '$SESSION', sshd (port $SSH_PORT), and emulator ($SERIAL) stay up."
        exit "$status"
    fi
    if [[ -n "$SSHD_PID" ]]; then kill "$SSHD_PID" 2>/dev/null || true; fi
    if [[ "$BOOTED_EMULATOR" == "1" && -n "$SERIAL" ]]; then
        adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        if [[ -n "$EMU_PID" ]]; then wait "$EMU_PID" 2>/dev/null || true; fi
    fi
    herdr session stop "$SESSION" >/dev/null 2>&1 || true
    herdr session delete "$SESSION" >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT

for tool in herdr agy adb jq ssh-keygen; do require "$tool"; done
SSHD_BIN="$(command -v sshd || echo /usr/sbin/sshd)"
[[ -x "$SSHD_BIN" ]] || fail "sshd is not available."
mkdir -p "$WORKDIR" "$AGY_CWD" "$SSHD_DIR"
: > "$REPORT"

# 1. Build the instrumented test APK.
if [[ "$SKIP_BUILD" != "1" ]]; then
    log "Build the :platform:android:ssh instrumented test APK."
    (cd "$REPO_ROOT" && ./gradlew -q :platform:android:ssh:assembleDebugAndroidTest)
fi
[[ -f "$TEST_APK" ]] || fail "Test APK not found at $TEST_APK"

# 2. Start an isolated Herdr session with agy.
herdr session stop "$SESSION" >/dev/null 2>&1 || true
herdr session delete "$SESSION" >/dev/null 2>&1 || true
log "Start the isolated Herdr session '$SESSION'."
setsid herdr --session "$SESSION" server >"$WORKDIR/herdr-server.log" 2>&1 < /dev/null &
for _ in $(seq 1 100); do
    [[ -S "$HERDR_SOCKET" ]] && herdr_s api snapshot >/dev/null 2>&1 && break
    sleep 0.1
done
herdr_s api snapshot >/dev/null 2>&1 || fail "The Herdr session did not start. See $WORKDIR/herdr-server.log"

PANE_ID="$(herdr_s workspace create --cwd "$AGY_CWD" --label agy-cursor --focus | jq -r '.result.root_pane.pane_id')"
[[ -n "$PANE_ID" && "$PANE_ID" != "null" ]] || fail "Could not create a Herdr workspace."
log "Run agy in pane $PANE_ID."
herdr_s pane run "$PANE_ID" agy >/dev/null
herdr_s pane wait-output "$PANE_ID" --source visible --regex '(?i)(trust the contents|Antigravity CLI)' --timeout 60000 >/dev/null \
    || fail "agy did not start."
if herdr_s pane read "$PANE_ID" --source visible --format text | grep -qi 'trust the contents'; then
    log "Accept the agy folder trust prompt."
    herdr_s pane send-keys "$PANE_ID" Enter >/dev/null
fi
herdr_s pane wait-output "$PANE_ID" --source visible --match 'Antigravity CLI' --timeout 60000 >/dev/null \
    || fail "agy did not show its prompt."
sleep 2
herdr_s pane read "$PANE_ID" --source visible --format text > "$WORKDIR/agy-pane-before.txt"

# 3. Start a private sshd that points at the isolated Herdr session.
# Use an Ed25519 host key, like a default OpenSSH server.
if [[ ! -f "$SSHD_DIR/host_ed25519" ]]; then
    ssh-keygen -q -t ed25519 -N '' -f "$SSHD_DIR/host_ed25519"
fi
: > "$SSHD_DIR/authorized_keys"
chmod 600 "$SSHD_DIR/authorized_keys"
cat > "$SSHD_DIR/sshd_config" <<EOF
Port $SSH_PORT
ListenAddress 127.0.0.1
HostKey $SSHD_DIR/host_ed25519
PidFile $SSHD_DIR/sshd.pid
AuthorizedKeysFile $SSHD_DIR/authorized_keys
AllowUsers $USER
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
SetEnv HERDR_SOCKET_PATH=$HERDR_SOCKET
LogLevel DEBUG1
EOF
"$SSHD_BIN" -t -f "$SSHD_DIR/sshd_config" || fail "The private sshd config is not valid."
log "Start the private sshd on 127.0.0.1:$SSH_PORT."
"$SSHD_BIN" -D -e -f "$SSHD_DIR/sshd_config" >"$SSHD_DIR/sshd.log" 2>&1 &
SSHD_PID=$!

# 4. Boot the headless emulator.
if [[ -z "$SERIAL" ]]; then
    SERIAL="emulator-$EMU_PORT"
    [[ -x "$EMULATOR_BIN" ]] || fail "Emulator not found at $EMULATOR_BIN"
    log "Boot the headless emulator '$AVD' as $SERIAL."
    "$EMULATOR_BIN" -avd "$AVD" -port "$EMU_PORT" -no-window -no-audio -no-boot-anim \
        -no-snapshot -read-only -gpu swiftshader_indirect >"$WORKDIR/emulator.log" 2>&1 &
    EMU_PID=$!
    BOOTED_EMULATOR=1
fi
timeout 300 adb -s "$SERIAL" wait-for-device || fail "The emulator did not connect to adb."
for _ in $(seq 1 300); do
    [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    sleep 1
done
[[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == "1" ]] || fail "The emulator did not boot."

# The emulator network starts some seconds after boot. Wait until the emulator can read the SSH
# banner from the host loopback. Older toybox nc (API 32 and lower) has no -z option.
sshd_reachable() {
    [[ "$(adb -s "$SERIAL" shell "nc -w 2 10.0.2.2 $SSH_PORT </dev/null 2>/dev/null | head -c 4" | tr -d '\r')" == "SSH-" ]]
}
log "Wait until the emulator can reach 10.0.2.2:$SSH_PORT."
for _ in $(seq 1 120); do
    sshd_reachable && break
    sleep 1
done
sshd_reachable || fail "The emulator cannot reach the private sshd at 10.0.2.2:$SSH_PORT."

log "Install the test APK on $SERIAL."
adb -s "$SERIAL" install -r -t "$TEST_APK" >/dev/null

run_test() {
    adb -s "$SERIAL" shell am instrument -w -r \
        -e class "$TEST_CLASS#$1" \
        -e bisonHost 10.0.2.2 \
        -e bisonPort "$SSH_PORT" \
        -e bisonUser "$USER" \
        "$TEST_PKG/$RUNNER"
}

# 5. Export the device key and authorize it on the private sshd.
adb -s "$SERIAL" logcat -c
run_test exportPublicKey > "$WORKDIR/instrument-export.txt"
PUBKEY="$(adb -s "$SERIAL" logcat -d -s "$LOG_TAG:I" | sed -n 's/.*PUBKEY //p' | tail -n 1 | tr -d '\r')"
[[ -n "$PUBKEY" ]] || fail "The test did not report a public key. See $WORKDIR/instrument-export.txt"
printf '%s\n' "$PUBKEY" > "$SSHD_DIR/authorized_keys"
log "Authorized the emulator key: ${PUBKEY:0:40}..."

# 6. Run the cursor check.
adb -s "$SERIAL" logcat -c
set +e
run_test frameCursorFollowsAgyPromptCaret > "$WORKDIR/instrument-cursor.txt"
set -e
adb -s "$SERIAL" logcat -d -v raw -s "$LOG_TAG:I" > "$WORKDIR/logcat-cursor.txt"
herdr_s pane read "$PANE_ID" --source visible --format text > "$WORKDIR/agy-pane-after.txt"

{
    echo "== Herdr pane $PANE_ID after the test (ground truth text) =="
    cat -n "$WORKDIR/agy-pane-after.txt"
    echo
    echo "== Frames that the app built (█ = frame cursor) =="
    cat "$WORKDIR/logcat-cursor.txt"
    echo
    echo "== Instrumentation result =="
    grep -E 'INSTRUMENTATION_STATUS: stack=|^OK|FAILURES|Tests run' "$WORKDIR/instrument-cursor.txt" | head -n 20
} > "$REPORT"
cat "$REPORT"

if grep -q '^OK (1 test)' "$WORKDIR/instrument-cursor.txt"; then
    log "PASS: the frame cursor is at the agy prompt caret."
    exit 0
fi
log "FAIL: the frame cursor is not at the agy prompt caret. Report: $REPORT"
exit 1
