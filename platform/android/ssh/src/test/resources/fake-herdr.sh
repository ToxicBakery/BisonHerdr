#!/bin/sh
# A fake herdr for the JVM tests of the agent alerts (issue #53). It keeps its state in "$HOME/state".
# - "herdr --version" writes a version.
# - "herdr api snapshot" writes the file snapshot.json, and adds "snapshot" to host.log.
# - "herdr remote-api-bridge" reads one request line, and adds it to requests.log.
#   For "events.subscribe", it writes "subscription_started". It then writes each file events/next.json until
#   the test closes the channel. It adds "started" and "stopped" to host.log.
#   For all other requests, it writes an empty result with the id of the request.
# A call with "--session <name>" first adds "session <name> <subcommand>" to host.log.
state="$HOME/state"
mkdir -p "$state/events"
if [ "$1" = --session ]; then
    echo "session $2 $3" >> "$state/host.log"
    shift 2
fi
case "$1" in
    --version)
        echo "herdr 0.9.5"
        ;;
    api)
        echo snapshot >> "$state/host.log"
        cat "$state/snapshot.json"
        ;;
    remote-api-bridge)
        IFS= read -r line
        printf '%s\n' "$line" >> "$state/requests.log"
        id=$(printf '%s\n' "$line" | sed -n 's/^{"id":"\([^"]*\)".*$/\1/p')
        case "$line" in
            *'"method":"events.subscribe"'*)
                trap 'echo stopped >> "$state/host.log"; exit 0' TERM HUP INT
                echo started >> "$state/host.log"
                printf '{"id":"%s","result":{"type":"subscription_started"}}\n' "$id"
                # The loop stops after about 30 s, so that no process stays after a test.
                n=0
                while [ "$n" -lt 600 ]; do
                    if mv "$state/events/next.json" "$state/events/sent.json" 2>/dev/null; then
                        cat "$state/events/sent.json"
                    fi
                    sleep 0.05
                    n=$((n + 1))
                done
                ;;
            *)
                printf '{"id":"%s","result":{"type":"ok"}}\n' "$id"
                ;;
        esac
        ;;
esac
