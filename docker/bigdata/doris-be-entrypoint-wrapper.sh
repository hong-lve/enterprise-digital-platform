#!/bin/bash
# apache/doris:be-* (the standalone BE image, distinct from the all-in-one
# FE+BE image the primary "doris" service uses) supports joining an existing
# FE via "assign mode" - FE_MASTER_IP/BE_IP/BE_PORT env vars - but its own
# validate_assign_mode() strictly regex-validates FE_MASTER_IP as a literal
# IP address, rejecting a hostname like "doris" outright. This compose file
# has no static IPs (default bridge network, dynamic addressing), so this
# wrapper resolves the FE's current IP via Docker's embedded DNS at
# container start instead of hardcoding one that would go stale across
# recreates.
set -eo pipefail

export FE_MASTER_IP="$(getent hosts doris | awk '{print $1}')"
export BE_IP="$(hostname -i | awk '{print $1}')"
export BE_PORT=9050

exec bash /usr/local/bin/entry_point.sh
