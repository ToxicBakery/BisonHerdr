#!/bin/sh
# A fake Herdr 0.9.5 for PaneKeyInputTest. It answers one request of `herdr remote-api-bridge`.
# It does not accept a request with a key name in $HOME/unknown-keys, as Herdr does not accept a name that it does
# not know. Then it writes nothing of the request. It writes each request that it accepts to $HOME/input.log.
# It holds a request with the text "a" until the file $HOME/release exists.
if [ "$1" != remote-api-bridge ]; then
    echo "herdr 0.9.5 (fake)"
    exit 0
fi
IFS= read -r request
id=$(printf '%s\n' "$request" | sed -n 's/^{"id":"\([^"]*\)".*/\1/p')
case $request in
    *'"method":"pane.read"'*)
        printf '{"id":"%s","result":{"type":"pane_read","read":{"pane_id":"w1:p1","text":"$ "}}}\n' "$id"
        exit 0
        ;;
    *'"text":"a"'*)
        : > "$HOME/held"
        tries=0
        while [ ! -e "$HOME/release" ] && [ "$tries" -lt 400 ]; do
            sleep 0.05
            tries=$((tries + 1))
        done
        ;;
esac
while IFS= read -r key; do
    case $request in
        *"\"$key\""*)
            printf '%s\n' "$request" >> "$HOME/not-accepted.log"
            printf '{"id":"%s","error":{"code":"invalid_key","message":"unsupported key %s"}}\n' "$id" "$key"
            exit 0
            ;;
    esac
done < "$HOME/unknown-keys"
printf '%s\n' "$request" >> "$HOME/input.log"
printf '{"id":"%s","result":{"type":"ok"}}\n' "$id"
