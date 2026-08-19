#!/usr/bin/env bash
# Shared helper: puts a model file where the app can actually read it.
# Sourced by fetch-model.sh and run-tests.sh.

# Do NOT `adb shell mkdir` the models directory. A directory created that way is
# owned by shell (uid 2000, mode drwxrws---), and the app then gets EACCES
# listing it -- even though it lives inside the app's own external files dir.
# The app has to create it. So: launch the app once, wait for the directory to
# appear, and only then push. Falls back to files/ (always app-owned), which
# refreshModels() also scans.
push_model() {
  local local_file="$1"
  local pkg="${PKG:-com.englus.spike}"
  local base="/sdcard/Android/data/$pkg/files"
  local models="$base/models"

  if ! adb shell pm list packages | grep -q "package:$pkg"; then
    echo "error: $pkg is not installed. Build and install it first." >&2
    return 1
  fi

  # Clear a stale shell-owned directory from an older run of this script.
  if adb shell "[ -d '$models' ]" && ! adb shell "run-as $pkg ls '$models' >/dev/null 2>&1"; then
    echo "removing shell-owned $models"
    adb shell rm -rf "$models"
  fi

  adb shell am start -W -n "$pkg/.MainActivity" >/dev/null 2>&1 || true
  adb shell "n=0; while [ ! -d '$models' ] && [ \$n -lt 20 ]; do sleep 1; n=\$((n+1)); done"
  adb shell am force-stop "$pkg" >/dev/null 2>&1 || true

  local dest="$models"
  if ! adb shell "[ -d '$models' ]"; then
    echo "warning: $models not created by the app; pushing to $base instead"
    dest="$base"
  fi

  echo "pushing to $dest"
  adb push "$local_file" "$dest/"
}
