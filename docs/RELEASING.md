# Releasing

## Version numbers

One version for both apps, `MAJOR.MINOR.PATCH`:

- `desktop/app/drivevault.py` → `APP_VERSION`
- `android/app/build.gradle.kts` → `versionName` and `versionCode` (`1.8.0` → `180`)
- a new section in [CHANGELOG.md](../CHANGELOG.md)

MINOR for new features, PATCH for fixes. A change that older apps can't read (such as a new password KDF) is called out
in the changelog.

## Checklist

1. Bump the version in the places above and write the changelog entry.
2. `python tests/run_all.py`: everything must pass.
3. Build the signed APK (below) and check its certificate.
4. Make the desktop zip: the contents of `desktop/` at the top level, `desktop/app/*` under `DriveVault/app/`,
   `CHANGELOG.md`, and the APK as `DriveVault-Android.apk`.
5. Name the files `DriveVault-vX.Y.Z.zip` and `DriveVault-Android-vX.Y.Z.apk`, and write `SHA256SUMS.txt`
   (`sha256sum *.zip *.apk > SHA256SUMS.txt`, or `Get-FileHash -Algorithm SHA256` on Windows).
6. Test-install the APK on an emulator or a spare phone, and run the zip's launcher on a test folder.
7. `gh release create vX.Y.Z <files> --title "DriveVault X.Y.Z" --notes-file <notes>`, then download the assets again
   and compare them with `SHA256SUMS.txt`.

## Android signing

Releases are signed with a release keystore that never goes into git (`android/signing/` is ignored).

Create it once. `keytool` comes with the JDK and asks for the password, so it never appears in your shell history:

```bash
keytool -genkeypair -v -keystore android/signing/release.jks -alias drivevault -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=DriveVault, O=DriveVault"
```

**Back up the keystore file and its password** somewhere safe and separate (for example a password manager plus an
offline copy). Without both, future versions can't be installed over the current app; users would have to uninstall first.

Build the signed APK. On Windows the script asks for the password and clears it afterwards:

```bash
powershell -ExecutionPolicy Bypass -File android/build-release.ps1
```

Anywhere else, set `DV_KEYSTORE` (path), `DV_KEY_ALIAS` (`drivevault`) and `DV_KS_PASS` in the environment for the one
command `./gradlew :app:assembleRelease --no-daemon`, then unset them. Without a keystore, Gradle produces an unsigned
`app-release-unsigned.apk`.

Check the result:

```bash
apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
```

The certificate must be `CN=DriveVault, O=DriveVault` with the same SHA-256 digest as the previous release.

## Desktop

The desktop app is Python source plus launcher scripts; there is no installer, and the files are **not code-signed**.
