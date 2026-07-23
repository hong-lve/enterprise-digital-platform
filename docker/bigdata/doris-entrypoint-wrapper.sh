#!/bin/bash
# Works around a real bug in apache/doris:doris-all-in-one's own
# /usr/local/bin/entry_point.sh: on every fresh container, _main()
# unconditionally calls add_priority_networks(), which appends
# "priority_networks = 127.0.0.1/24" to be.conf, and register_be_to_fe()
# then registers the backend with FE using the hard-coded string
# CURRENT_BE_IP="127.0.0.1" - it does not read this back from be.conf.
# That address only resolves to "this container" from inside FE's own
# process; any other container (Flink, in our case) trying to Stream Load
# through that backend gets "Connection refused" / "no available backend".
#
# There's no way to prevent this by pre-seeding be.conf - the hard-coded
# 127.0.0.1 registration happens regardless. So this wrapper runs the
# real entrypoint unmodified, waits for it to finish its (wrong) BE
# registration, then corrects it in place: fix be.conf, restart just the
# BE process, drop the bad registration, and re-register with the
# container's actual Docker network address. This is exactly the manual
# sequence that was verified live against a real cross-container Flink
# CDC-to-Doris job (see flink-connectors' sibling ClickHouse work and the
# realtime-compute-service SQL 流作业 feature).

set -eo pipefail

DORIS_HOME="/opt/apache-doris"
REAL_IP="$(hostname -i | awk '{print $1}')"

echo "[doris-network-fix] container network address: ${REAL_IP}"

# Run the image's own entrypoint unmodified, in the background - it starts
# FE, starts BE (which will register itself as 127.0.0.1), and would
# otherwise just `exec "$@"` and block forever. Invoked via `bash` rather
# than executed directly - relying on this file's own execute bit inside
# the image was unreliable (hit a real "Permission denied" running this
# under a bash-based entrypoint override).
bash /usr/local/bin/entry_point.sh &
ENTRY_PID=$!

mysql_query() {
    mysql -uroot -P9030 -h127.0.0.1 --connect-timeout=2 --comments -e "$1" 2>/dev/null
}

# A plain `grep -q "127.0.0.1"` on the output is not safe here: when FE
# isn't reachable yet, the mysql client's own connection-refused error text
# also contains "127.0.0.1" and would match, making this loop declare
# success before FE/BE ever actually started (hit this for real - the
# fallout was trying to restart a BE that never ran and hanging forever).
# Check the exit code first so a connection failure is only ever treated
# as "not ready yet".
echo "[doris-network-fix] waiting for BE to finish its (wrong) 127.0.0.1 registration..."
for i in $(seq 1 300); do
    if backends_output=$(mysql_query "SHOW BACKENDS") && echo "$backends_output" | grep -q "127.0.0.1"; then
        echo "[doris-network-fix] BE registered as 127.0.0.1, proceeding to fix"
        break
    fi
    sleep 1
done

echo "[doris-network-fix] correcting be.conf priority_networks to ${REAL_IP}/24"
sed -i "s#priority_networks = 127.0.0.1/24#priority_networks = ${REAL_IP}/24#" "${DORIS_HOME}/be/conf/be.conf"

echo "[doris-network-fix] restarting BE process to pick up the corrected config"
bash "${DORIS_HOME}/be/bin/stop_be.sh" || true
sleep 2
bash "${DORIS_HOME}/be/bin/start_be.sh" --daemon

echo "[doris-network-fix] waiting for BE to come back up locally..."
for i in $(seq 1 300); do
    if curl -s -m 2 "http://127.0.0.1:8040/api/health" >/dev/null 2>&1; then
        echo "[doris-network-fix] BE is back up"
        break
    fi
    sleep 1
done

echo "[doris-network-fix] dropping the stale 127.0.0.1 backend registration"
# Doris really does require the extra P here - confirmed live that plain
# "DROP BACKEND" is refused with "It is highly NOT RECOMMENDED to use DROP
# BACKEND stmt ... If you insist, use DROPP instead of DROP". It's an
# intentional typo-as-confirmation safety guard against accidental data
# loss, not a bug - "fixing" it to DROP makes the statement fail (silently,
# behind the `|| true` below) and leaves the stale 127.0.0.1 registration
# in place permanently, which is exactly what happened here.
mysql_query "ALTER SYSTEM DROPP BACKEND '127.0.0.1:9050'" || true

echo "[doris-network-fix] re-registering backend as ${REAL_IP}:9050"
for i in $(seq 1 60); do
    if mysql_query "ALTER SYSTEM ADD BACKEND '${REAL_IP}:9050'"; then
        echo "[doris-network-fix] re-registered successfully"
        break
    fi
    sleep 1
done

echo "[doris-network-fix] done - Doris backend is now reachable from other containers at ${REAL_IP}"

# Not `wait "$ENTRY_PID"` - confirmed live that the image's own entry_point.sh
# does NOT block forever the way this script originally assumed: it starts
# FE and BE as detached daemons and then returns on its own, well before
# either service is actually done running. Waiting on it meant this
# wrapper (the container's PID 1) exited minutes after boot - with FE/BE
# still healthy as daemons underneath - and Docker's `restart:
# unless-stopped` policy then killed and restarted the *entire* container
# on that exit, over and over (~75-110s apart, every time), which is what
# was actually causing the repeated "wait catalog to be ready" hangs and
# stale bdbje membership this wrapper's IP-correction logic exists to
# avoid in the first place - the fix upstream of that fix. FE/BE don't need
# this script's own process to stay alive once launched, so just block
# forever here instead of tying the container's lifetime to entry_point.sh's.
tail -f /dev/null
