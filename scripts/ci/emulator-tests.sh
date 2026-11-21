#!/usr/bin/env bash
# Everything the emulator job runs on the booted emulator: the adb smoke test (scripts/ci/emulator-smoke.sh),
# then the instrumented tests when there are androidTest sources. Both always run; the job fails if either
# fails, or if connectedDebugAndroidTest ran no test at all (an install failure does not fail the Gradle task).
#
#   scripts/ci/emulator-tests.sh <apk-or-dir> <out-dir>
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

bash "$ROOT/scripts/ci/emulator-smoke.sh" "$1" "$2"
smoke=$?

connected=0
if compgen -G "$ROOT/android/*/src/androidTest" > /dev/null; then
  echo "::group::connectedDebugAndroidTest"
  # The smoke test's APK is signed with the build job's debug key and leaves app data behind: start clean.
  adb uninstall cz.offlinka.app.debug > /dev/null 2>&1 || true
  adb uninstall cz.offlinka.app.debug.test > /dev/null 2>&1 || true
  (cd "$ROOT/android" && ./gradlew --no-daemon connectedDebugAndroidTest)
  connected=$?
  ran="$(find "$ROOT/android" -path '*/build/outputs/androidTest-results/connected/*' -name 'TEST-*.xml' \
    -exec grep -ho '<testcase ' {} + 2>/dev/null | wc -l)"
  echo "instrumented tests run: $ran"
  if [ "$connected" -eq 0 ] && [ "$ran" -eq 0 ]; then
    echo "::error title=connectedDebugAndroidTest::no instrumented test ran (see the install errors above)"
    connected=1
  fi
  echo "::endgroup::"
else
  echo "No androidTest sources: connectedDebugAndroidTest skipped."
fi

[ "$smoke" -eq 0 ] || echo "::error title=Emulator smoke test::failed (exit $smoke)"
[ "$connected" -eq 0 ] || echo "::error title=connectedDebugAndroidTest::failed"
[ "$smoke" -eq 0 ] && [ "$connected" -eq 0 ]
