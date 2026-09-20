# FBI Remote Install Enhanced (Android Kotlin)

This repository now contains an Android Kotlin implementation of the original Python remote install server.

## Feature parity implemented

- Serves `.cia`, `.tik`, `.cetk`, `.3dsx` files over HTTP.
- Accepts a single file path or directory path (directory scan is non-recursive).
- Builds FBI remote-install URL payloads and pushes them to `3DS_IP:5000` with a 4-byte length prefix.
- Supports retries, retry delay, connect timeout, and ACK wait.
- Supports no-send and copy-only startup behavior.
- Stores recent 3DS IP history.
- Detects host IPv4 and allows manual override.
- Uses a Material 3 app theme with edge-to-edge content handling.
- Includes an in-app file picker that imports supported files into app storage.
- Writes timestamped logs under app internal storage (`logs/servefiles_log_*.txt`).
- Provides resend and stop controls in-app.

## Build debug APK

```bash
./gradlew :app:assembleDebug
```

Output:

`/home/runner/work/FBI-Remote-Install-Server/FBI-Remote-Install-Server/app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions release workflow

Workflow file: `.github/workflows/android-debug-release.yml`

- Triggered on:
  - `workflow_dispatch`
  - tag pushes matching `v*`
- Builds `app-debug.apk`
- Publishes the APK to GitHub Releases as a prerelease asset

## Legacy Python scripts

The original Python implementation is still present (`servefiles.py`, `.bat`, `.sh`) for reference.
