#!/usr/bin/env bash
# Runs inside the emulator that creates the cached AVD snapshot (avd-setup, main only). The runner
# saves the snapshot when this exits, so it waits for the system to settle first (#61).
set -uo pipefail
. "$(dirname "$0")/emulator-lib.sh"
settle_system 300 30
