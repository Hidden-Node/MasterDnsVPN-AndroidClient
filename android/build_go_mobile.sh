#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v gomobile >/dev/null 2>&1; then
  echo "gomobile not found. installing..."
  go install golang.org/x/mobile/cmd/gomobile@latest
fi

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
