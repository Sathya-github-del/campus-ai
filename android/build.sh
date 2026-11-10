#!/usr/bin/env bash
# Build wrapper: sets the portable toolchain env and keeps Android user state inside the project.
#   android/build.sh assembleDebug testDebugUnitTest
#   android/build.sh assembleDebug -PapiBaseUrl=http://192.168.1.20:8000   (physical phone on the LAN)
set -euo pipefail
cd "$(dirname "$0")"
[ -f ../env.sh ] && source ../env.sh   # dev-laptop toolchain paths (optional, not in git)
# Fall back to the same portable layout TileOps uses when env.sh is absent.
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.local/jdk/current" ]; then export JAVA_HOME="$HOME/.local/jdk/current"; fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_USER_HOME="$PWD/.android-user"
mkdir -p "$ANDROID_USER_HOME"
echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
exec ./gradlew --console=plain "$@"
