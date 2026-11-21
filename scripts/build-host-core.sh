#!/usr/bin/env bash
# Host build of offlinka-core (linux x86_64 here) for the Android JVM tests (JNA) and
# offlinka-cli. Same as the host part of gen-bindings.sh, without regenerating bindings.
#
# Output: core/target/host/linux-x86-64/libofflinka_core.so, core/target/host/offlinka-cli
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$HOME/opt/offlinka-env.sh" ] && source "$HOME/opt/offlinka-env.sh"
source "$ROOT/scripts/lib-lock.sh"
cd "$ROOT/core"
run cargo build -j 4 --profile host -p offlinka-core
case "$(uname -s)" in Darwin) EXT=dylib; OS=darwin ;; *) EXT=so; OS=linux ;; esac
case "$(uname -m)" in x86_64|amd64) ARCH=x86-64 ;; arm64|aarch64) ARCH=aarch64 ;; *) ARCH="$(uname -m)" ;; esac
mkdir -p "target/host/$OS-$ARCH"
cp "target/host/libofflinka_core.$EXT" "target/host/$OS-$ARCH/"
echo "$ROOT/core/target/host/$OS-$ARCH/libofflinka_core.$EXT"
