# Argon

An Android browser built on the [Servo](https://github.com/servo/servo) web engine, with a dark, minimal interface, built-in ad blocking, and a speed-dial start page.

**Status: alpha.** Releases are device-tested on Firebase Test Lab before they are published; each release on the Releases page states exactly what was tested, what passed, and what remains unsupported. Download the APK from the release you want and install it directly.

## Features

- Servo engine (modern, memory-safe rendering core written in Rust)
- Built-in ad and tracker blocking (StevenBlack unified hosts list, 77,759 domains)
- Speed-dial start page with bookmarks, plus full history
- Google, DuckDuckGo, Brave and Bing search from the omnibox
- Dark interface designed with the [unslop](https://github.com/heydamir/unslop) method

## Building

Requirements: JDK 21, the Android SDK (platform 36, build-tools 36.0.0), and about 90 MB for the engine download.

```bash
tools/fetch-engine.sh        # downloads the pinned Servo engine nightly into app/src/main/jniLibs/
./gradlew assembleRelease    # produces app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign the unsigned APK with your own keystore using apksigner from the Android SDK build-tools. The release keystore used for official Argon builds is private and is not part of this repository.

### Engine provenance

- Engine source: [`servo/servo@b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd`](https://github.com/servo/servo/tree/b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd) (nightly 2026-11-05)
- Prebuilt binary: `servo-aarch64-android.apk`, sha256 `22c470fc49207cfef7ff79e9cce370247d30f8cc14a229629b162debae994278`
- `tools/fetch-engine.sh` verifies this hash. Nightlies rotate; if the download no longer matches, the byte-exact engine APK used for each Argon release is attached to that release on the Releases page.

The `org.servo.servoview` JNI glue classes are adapted from Servo's own servoshell Android app (MPL-2.0). See `app/src/main/assets/NOTICES.txt` for full third-party notices, also shipped inside the app.

## Credits

- The [Servo project](https://servo.org/) and its contributors for the engine
- [StevenBlack/hosts](https://github.com/StevenBlack/hosts) for the block list (MIT)
- Damir Sokolovsky ([@heydamir](https://github.com/heydamir)) for [unslop](https://github.com/heydamir/unslop), the interface design method used for Argon's UI

## License

Mozilla Public License 2.0 - see [LICENSE](LICENSE).
