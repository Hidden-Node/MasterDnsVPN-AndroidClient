# Android build notes

This Android client wraps the shared Go engine through `gomobile`.
Android build helpers must not edit the shared Go module files at the repository root.

Use the provided scripts to rebuild the Android AAR:

```bash
./android/build_go_mobile.sh
```

```bat
android\build_go_mobile.bat
```

Both scripts:

- read the pinned gomobile/gobind version from `android/gomobile.version`
- run gomobile binding from an isolated temporary source tree
- run any `go get` dependency preparation only in the temporary tree
- fail if the real `go.mod` or `go.sum` are modified

If the build reports missing Go module dependencies, update the shared Go module in a separate upstream/core change rather than from the Android build script.

## AAR check

`android/app/libs/masterdnsvpn.aar` is a gitignored build artifact: rebuild it after any change under `mobile/`.
Every app build runs the `verifyMobileAar` Gradle task, which fails with an explicit message when the AAR is missing, unreadable, or still carries the removed legacy engine (`tun2socks` / `FakeDNS`).
When the build reports a missing, unreadable or legacy AAR, re-run the AAR build script for your platform (see above) and rebuild.
