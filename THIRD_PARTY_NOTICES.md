# Third-party notices

DriveVault itself is released under the [MIT License](LICENSE). It includes or depends on the components below.

## Included in the desktop app (on the drive)

| Component | Version | License | Used for |
|---|---|---|---|
| [mammoth.js](https://github.com/mwilliamson/mammoth.js) (`desktop/app/mammoth.min.js`) | bundled build | BSD-2-Clause, see [desktop/app/mammoth.LICENSE.txt](desktop/app/mammoth.LICENSE.txt) | Showing Word (.docx) files in the viewer |

## Installed on the computer by the launchers (not bundled)

| Component | License | Used for |
|---|---|---|
| [cryptography](https://cryptography.io) | Apache-2.0 or BSD-3-Clause | AES-GCM, PBKDF2, Argon2id (version 44 or newer) |
| [argon2-cffi](https://github.com/hynek/argon2-cffi) (only if `cryptography` is older than 44) | MIT | Argon2id |

## Included in the Android app (APK)

| Component | Version | License |
|---|---|---|
| [Bouncy Castle](https://www.bouncycastle.org) `bcprov-jdk18on` (Argon2id) | 1.80 | MIT, see [LICENSES/BouncyCastle-MIT.txt](LICENSES/BouncyCastle-MIT.txt) |
| [Kotlin](https://kotlinlang.org) standard library | 2.1.0 | Apache-2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON) | 1.7.3 | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.9.0 | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI, Foundation, Material 3, Material icons) | BOM 2024.12.01 | Apache-2.0 |
| AndroidX Activity, Core, Lifecycle, DocumentFile | 1.9.3, 1.15.0, 2.8.7, 1.0.1 | Apache-2.0 |
| [Media3 ExoPlayer and UI](https://developer.android.com/media/media3) | 1.5.1 | Apache-2.0 |

The Apache License 2.0 text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

## Used only for building and testing (not distributed)

Gradle and the Android Gradle Plugin, JUnit 4, Robolectric, kotlin-test (Kotlin tests), and
[Playwright for Python](https://playwright.dev/python/) (browser tests). Each is available under its own open-source license.
