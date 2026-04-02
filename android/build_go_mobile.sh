#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MOBILE_TOOLS_VERSION="v0.0.0-20231127183840-76ac6878050a"

# Always install a pinned, known-good gomobile/gobind pair.
go install "golang.org/x/mobile/cmd/gomobile@${MOBILE_TOOLS_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${MOBILE_TOOLS_VERSION}"

export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

mkdir -p android/app/libs

gomobile bind \
  -v \
  -target=android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 21 \
  -o android/app/libs/masterdnsvpn.aar \
  ./mobile/

echo "Built android/app/libs/masterdnsvpn.aar"
