#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MOBILE_TOOLS_VERSION="$(tr -d '[:space:]' < android/gomobile.version)"
OUTPUT_AAR="$ROOT_DIR/android/app/libs/masterdnsvpn.aar"

check_go_modules_clean() {
  if ! git diff --quiet -- go.mod go.sum; then
    echo "ERROR: gomobile build modified shared Go module files."
    echo "Refusing to continue because android builds must not change go.mod/go.sum."
    git diff -- go.mod go.sum
    exit 1
  fi
}

trap check_go_modules_clean EXIT
check_go_modules_clean

# Always install a pinned, known-good gomobile/gobind pair.
go install "golang.org/x/mobile/cmd/gomobile@${MOBILE_TOOLS_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${MOBILE_TOOLS_VERSION}"

export PATH="$(go env GOPATH)/bin:$PATH"
GO111MODULE=on gomobile init

mkdir -p android/app/libs

BUILD_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$BUILD_DIR"
  check_go_modules_clean
}
trap cleanup EXIT

git archive HEAD | tar -x -C "$BUILD_DIR"
cd "$BUILD_DIR"

# gomobile's generated binding imports golang.org/x/mobile/bind. Resolve that
# dependency only in the temporary build tree so the real go.mod/go.sum stay
# untouched for upstream/core merge safety.
GO111MODULE=on go get "golang.org/x/mobile@${MOBILE_TOOLS_VERSION}"

gomobile bind \
  -v \
  -target=android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 21 \
  -o "$OUTPUT_AAR" \
  ./mobile/

echo "Built android/app/libs/masterdnsvpn.aar"
