# Sourced by the build scripts: `run <cmd>` runs a heavy command under the shared
# build lock of this box (see docs/SPEC.md, working rules).
LOCK=/tmp/claude-1000/offlinka-app-build.lock
# The shared build lock. Skipped when a parent process already holds it (callers
# often wrap this script in `flock … offlinka-app-build.lock`; taking it again here
# would deadlock) or when OFFLINKA_NO_LOCK is set.
lock_held_by_parent() {
  local p=$PPID
  while [ -n "$p" ] && [ "$p" -gt 1 ]; do
    if [ "$(cat "/proc/$p/comm" 2>/dev/null)" = flock ] \
      && tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "offlinka-app-build.lock"; then return 0; fi
    p=$(awk '{print $4}' "/proc/$p/stat" 2>/dev/null || echo 1)
  done
  return 1
}
run() {
  if [ -n "${OFFLINKA_NO_LOCK:-}" ] || ! command -v flock >/dev/null || lock_held_by_parent; then "$@"
  else mkdir -p "$(dirname "$LOCK")"; flock "$LOCK" nice -n 10 timeout 3600 "$@"; fi
}
