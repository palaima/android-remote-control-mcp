# Emulator smoke test

`smoke.py` calls every MCP tool on a running Android emulator and checks the result outside the app.
The checks use a uiautomator dump, `dumpsys`, `adb shell cat`/`stat`/`ls`, `ps`, or a small touch-probe web page.
A tool passes only when the device state agrees with what the tool reported.

The E2E suite in `e2e-tests/` runs on redroid with Android 14 only.
This smoke test covers real emulators, especially API 30 to 32 (Android 11 to 12L).
Those levels have different Settings layouts and need Shizuku for some typing fallbacks.

## Files

| file | purpose |
|---|---|
| `smoke.py` | The harness. Python 3 standard library only. |
| `setup_device.sh` | Prepares one emulator: installs the APKs, enables the accessibility service, configures and starts the MCP server, forwards the port, starts Shizuku. |
| `test-app/` | Source and build script of a small test app (`dev.test.nosettext`). Its first text field refuses `ACTION_SET_TEXT`. Its second field is a plain `EditText`. |

## Prerequisites

- Android SDK with `platform-tools`, `build-tools` and one platform installed. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), or put `adb` on `PATH`.
- JDK 11 or newer (for `test-app/build.sh`; `d8` does not run on Java 8).
- Python 3.8 or newer and `curl`.
- The gms debug APK: `make build`.
- Optional APKs. They are never downloaded by the scripts; pass their paths in the environment:
  - `SHIZUKU_APK`: Shizuku, from <https://github.com/RikkaApps/Shizuku/releases>. API 30 to 32 need it for the guarded-field typing check.
  - `TERMUX_APK`: Termux, from <https://github.com/termux/termux-app/releases>. Only used to check that `android_list_apps` lists user apps.
  - `WEBVIEW_SHELL_APK`: the WebView shell (`org.chromium.webview_shell`). AOSP images ship it as `/product/app/Browser2/Browser2.apk`; pull it with `adb pull` from such an image. It needs API 32 or higher. Without it, the harness uses Chrome.

## Emulators used

All emulators are arm64 tablets with these AVD settings:

- Screen 2560x1600, landscape.
- `hw.camera.back=virtualscene`
- `hw.camera.front=emulated`

| API | Android | system image |
|---|---|---|
| 30 | 11 | `system-images;android-30;google_apis;arm64-v8a` |
| 32 | 12L | AOSP image without Google Play services (two-pane Settings) |
| 34 | 14 | `system-images;android-34;google_apis;arm64-v8a` |

The harness reads the screen size from the device (`wm size` and the display rotation), so other sizes should work.
The Settings checks expect English (US) as the system language.

## Run

1. Build the app and the test app:

   ```bash
   make build
   scripts/emulator-smoke/test-app/build.sh
   ```

   The test app APK is written to `build/emulator-smoke/test-app/nosettext.apk`.

2. Start the emulator and prepare it. Use one host port per emulator:

   ```bash
   MCP_TOKEN=smoke-test-token SHIZUKU_APK=/path/to/shizuku.apk \
     scripts/emulator-smoke/setup_device.sh emulator-5554 18090
   ```

   The script prints the number of tools the server reports.

3. Authorize Shizuku once per device (API 30 to 32 only; skip on API 33 and higher).
   `setup_device.sh` already turns on "Allow elevated commands" and starts the Shizuku server.
   In the app, open **Settings > Shell** and tap:
   1. **Restart app**.
   2. **Grant access**.
   3. **Allow all the time** in the Shizuku dialog.

   The Shell screen then shows "Ready, commands run as uid 2000".
   `pm grant ... moe.shizuku.manager.permission.API_V23` alone is not enough.
   After an emulator restart, run `setup_device.sh` again to restart the Shizuku server.

4. Run the harness:

   ```bash
   make test-emulator-smoke SERIAL=emulator-5554 PORT=18090 TOKEN=smoke-test-token
   ```

   or directly:

   ```bash
   python3 scripts/emulator-smoke/smoke.py --serial emulator-5554 --port 18090 --token smoke-test-token
   ```

   The flags can also be set with `ADB_SERIAL`, `MCP_PORT`, `MCP_TOKEN` and `SMOKE_OUT`.
   A full run takes 5 to 10 minutes. Do not touch the emulator while it runs.
   Several emulators can run at the same time, each with its own host port.

The harness changes the device and puts it back afterwards:

- It turns on Allow Write and Allow Delete for Downloads in the app, through the app's UI, and restores the old values.
- It creates `armcp_*` files in `Download` and deletes them.
- It serves the touch-probe page through `adb reverse` and removes the rule.
- On Chrome, it disables the first-run screens with `/data/local/tmp/chrome-command-line` and `am set-debug-app`, and undoes both.
- It sets a mock location with `adb emu geo fix`.
- It turns off auto-rotate (`accelerometer_rotation 0`) and leaves it off.

## Read the results

Each check prints one line while the run goes on:

```text
  [PASS] android_tap: tap(213,1111) opened Storage (dump 'Free up space')
```

The status is `PASS`, `FAIL`, `SKIP` or `INFO`. `INFO` lines add diagnostics and do not count.
A tool is `FAIL` if any of its checks failed, `PASS` if at least one passed and none failed, and `SKIP` if every check was skipped.

At the end the harness prints:

- **STALE-STATE PROBES**: whether screen-reading tools return the new state after the screen changed outside the tool. `NEW` is the expected answer.
- **SUMMARY**: one line per tool, then the totals.

Results are written to `build/emulator-smoke/<serial>/results-<serial>.json` (or the `--out` directory).
The exit code is 1 when any tool is `FAIL` or was not run, or when a test group raised an exception.

## Known results

Totals from the last run of the version in this directory:

| API | PASS | FAIL | SKIP |
|---|---|---|---|
| 30 | 57 | 2 | 0 |
| 32 | 58 | 0 | 1 |
| 34 | 59 | 0 | 0 |

All other tools pass on all three levels.

| tool | API 30 | API 32 | API 34 | cause |
|---|---|---|---|---|
| `android_take_camera_photo` | FAIL | PASS | PASS | Emulator fault on API 30, see below. |
| `android_save_camera_video` | FAIL | PASS | PASS | Emulator fault on API 30, see below. |
| `android_get_location` | PASS | SKIP | PASS | The API 32 image has no Google Play services. |

### Fixed app bugs

Two bugs found by this harness are fixed. Both also occurred in the upstream build at commit `f0ed8f87`. Both fixes need Shizuku and "Allow elevated commands", which `setup_device.sh` turns on.

1. `android_get_clipboard` returned `{"text":null}` while the app was in the background, because Android lets only the focused app and the default keyboard read the clipboard.
   With Shizuku, the tool now reads the clipboard as the shell user, which may read it in the background.
   Without Shizuku, the tool returns an error instead of `null` when it gets no clip while the app is in the background.
2. `android_close_app` reported success on API 32 and 34, but the process kept running, because `killBackgroundProcesses` only stops cached background processes.
   With Shizuku, the tool now runs `am force-stop` as the shell user.
   Without Shizuku, it still asks Android to kill the cached background processes and says that it cannot verify the result.

### API 30 back camera (emulator fault)

On the API 30 image, the `virtualscene` back camera ("0") shows a preview but delivers no captured frames:

- `android_take_camera_photo` on camera "0" times out after 45 s.
- `android_save_camera_video` on camera "0" fails with CameraX error code 8 (no valid data).
- The stock Camera app cannot save a photo from that camera either. Logcat shows `Camera3-Stream: returnBuffer: Stream 1: timestamp ... is not increasing` from cameraserver.

The same tools work on the front camera "1"; the harness retries there and logs the result as `INFO`.
The app could report the missing frames sooner instead of waiting for the full timeout.

## Differences between API levels

- **Settings layout:** API 32 uses two-pane Settings, and uiautomator only sees the right pane. The harness then takes the left-pane positions from the tool and verifies the result with the dump. Item names differ: API 30 has "Apps & notifications", API 34 has "Sound & vibration".
- **Cameras:** On API 30 and 32 the back camera is "0" and the front camera is "1". On API 34 the front camera is "1" and the back camera is "10". The harness reads the IDs from `android_list_cameras`.
- **Location:** On API 30 and 34, `android_get_location` is checked against `dumpsys location` after `adb emu geo fix`.
- **Browser:** The WebView shell needs API 32 or higher, so API 30 uses Chrome for `android_open_uri` and the touch probe.
- **Guarded text field:** API 30 to 32 need Shizuku and "Allow elevated commands" to type into a field that refuses `ACTION_SET_TEXT`. API 33 and higher do not.
