#!/bin/sh
# Long-running entry point inside the rootfs. Starts sshd, then
# supervises the two opencode serves; the Android app supervises this
# proot process from outside and restarts it if it exits.
#
# OpenCode v1 and v2 coexist as separate serves with separate
# switches (Moriya's decision, 2026-11-26):
#   v1: /usr/local/bin/opencode-v1 (1.19.4), port 4096, HOME
#       /root/oc-v1-home, work dir /root/work (the original repo)
#   v2: /usr/local/bin/opencode-v2 (2.4.1), port 4098, HOME
#       /root/oc-v2-home, work dir /root/work-v2
# Port 4097 is deliberately NOT used: AndCode's local serve binds it
# on moto g05. Each version is enabled by the presence of a flag file
# in /opt/phonenode (opencode-v1.enabled / opencode-v2.enabled),
# written host-side by the Android app; absence means disabled.
mkdir -p /run/sshd /var/run/phonenode
/usr/sbin/sshd
echo "run-node: sshd started"

FLAGDIR=/opt/phonenode
RUNDIR=/var/run/phonenode

# -- self-install -------------------------------------------------------
# Existing nodes carry the .setup-done marker, so node-setup.sh never
# reruns for them: install or refresh each version's binary here, from
# the overlay tarballs the app recopies on every service start. The
# installed marker stores the tarball's sha256, so an app update that
# ships a new binary is picked up on the next service start.
ensure_binary() {
    # $1 = version label (v1|v2), $2 = tarball name, $3 = binary name
    tgz="$FLAGDIR/$2"
    bin="/usr/local/bin/$3"
    marker="/usr/local/share/phonenode/installed-$1"
    [ -f "$tgz" ] || return 0
    sum=$(sha256sum "$tgz" | cut -d' ' -f1)
    if [ -x "$bin" ] && [ -f "$marker" ] && [ "$(cat "$marker")" = "$sum" ]; then
        return 0
    fi
    echo "run-node: installing $3 from $2"
    tmp=$(mktemp -d)
    if tar -xzf "$tgz" -C "$tmp" && [ -f "$tmp/opencode" ]; then
        mkdir -p /usr/local/share/phonenode
        mv "$tmp/opencode" "$bin"
        chmod 755 "$bin"
        echo "$sum" > "$marker"
    else
        echo "run-node: FAILED to install $3 from $2"
    fi
    rm -rf "$tmp"
}

ensure_workdir() {
    # $1 = work dir. opencode stalls outside a git repository, so each
    # version's serve runs inside its own repo.
    dir="$1"
    mkdir -p "$dir"
    if [ ! -d "$dir/.git" ]; then
        ( cd "$dir" && git init -q \
          && git config user.email "node@phonenode.local" \
          && git config user.name "phonenode" )
    fi
}

ensure_binary v1 opencode.tgz opencode-v1
ensure_binary v2 opencode-v2.tgz opencode-v2
ensure_workdir /root/work
ensure_workdir /root/work-v2
mkdir -p /root/oc-v1-home /root/oc-v2-home

# -- per-version serve loop ----------------------------------------------
# Runs while the version's flag file exists: starts the serve, waits
# for it, and restarts it 5s after an exit (the historical behaviour).
# The serve's PID is recorded so the supervisor can kill exactly this
# version — never the other version, never sshd.
serve_loop() {
    # $1 label, $2 binary, $3 port, $4 home, $5 work dir, $6 log file
    label="$1"; bin="$2"; port="$3"; home="$4"; work="$5"; log="$6"
    pidfile="$RUNDIR/opencode-$label.pid"
    while [ -f "$FLAGDIR/opencode-$label.enabled" ]; do
        if [ ! -x "$bin" ]; then
            echo "run-node: $bin missing, cannot start opencode $label" >> "$log"
            sleep 5
            continue
        fi
        mkdir -p "$home"
        ( cd "$work" && HOME="$home" \
            XDG_DATA_HOME="$home/data" XDG_CONFIG_HOME="$home/config" \
            exec "$bin" serve --port "$port" --hostname 0.0.0.0 \
            --cors https://ysk-agent.github.io --cors null ) \
            >> "$log" 2>&1 &
        echo $! > "$pidfile"
        wait $!
        rm -f "$pidfile"
        [ -f "$FLAGDIR/opencode-$label.enabled" ] || break
        sleep 5
    done
}

pid_alive() {
    # $1 = pid (may be empty)
    [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

V1_LOOP_PID=""
V2_LOOP_PID=""

echo "run-node: opencode supervisor started, idling"
while true; do
    # v1
    if [ -f "$FLAGDIR/opencode-v1.enabled" ]; then
        if ! pid_alive "$V1_LOOP_PID"; then
            serve_loop v1 /usr/local/bin/opencode-v1 4096 \
                /root/oc-v1-home /root/work /var/log/opencode-v1.log &
            V1_LOOP_PID=$!
            echo "run-node: opencode v1 loop started (pid $V1_LOOP_PID)"
        fi
    elif pid_alive "$V1_LOOP_PID"; then
        # Disabled: kill this version's serve first, then its loop.
        [ -f "$RUNDIR/opencode-v1.pid" ] && kill "$(cat "$RUNDIR/opencode-v1.pid")" 2>/dev/null
        kill "$V1_LOOP_PID" 2>/dev/null
        wait "$V1_LOOP_PID" 2>/dev/null
        V1_LOOP_PID=""
        echo "run-node: opencode v1 stopped"
    fi
    # v2
    if [ -f "$FLAGDIR/opencode-v2.enabled" ]; then
        if ! pid_alive "$V2_LOOP_PID"; then
            serve_loop v2 /usr/local/bin/opencode-v2 4098 \
                /root/oc-v2-home /root/work-v2 /var/log/opencode-v2.log &
            V2_LOOP_PID=$!
            echo "run-node: opencode v2 loop started (pid $V2_LOOP_PID)"
        fi
    elif pid_alive "$V2_LOOP_PID"; then
        [ -f "$RUNDIR/opencode-v2.pid" ] && kill "$(cat "$RUNDIR/opencode-v2.pid")" 2>/dev/null
        kill "$V2_LOOP_PID" 2>/dev/null
        wait "$V2_LOOP_PID" 2>/dev/null
        V2_LOOP_PID=""
        echo "run-node: opencode v2 stopped"
    fi
    sleep 3
done
