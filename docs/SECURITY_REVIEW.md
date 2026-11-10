# Security review

What is checked, the result for version 1.8.0, and how to repeat each check. This is the maintainer's own review, not an
independent audit. The design and threat model are in [SECURITY.md](../SECURITY.md).

| # | Check | Result (1.8.0) |
|---|---|---|
| 1 | Only well-known crypto libraries are used; no home-made primitives | Pass |
| 2 | New vaults and password changes use Argon2id; older PBKDF2 vaults still open | Pass |
| 3 | Edited, reordered and truncated file data is detected | Pass |
| 4 | Removed or edited history entries are detected | Pass |
| 5 | Python and Kotlin produce and read the same vault bytes | Pass |
| 6 | The desktop server is reachable only from this computer and only with the session cookie | Pass |
| 7 | Vault files shown in the browser can't run scripts in the app's page | Pass |
| 8 | Decrypted data isn't cached by the browser; thumbnails aren't stored | Pass |
| 9 | The Android app has no internet permission, blocks screenshots and is excluded from backups | Pass |
| 10 | No secrets, keystores, vault data or personal data in the repository | Pass |
| 11 | Dependencies are checked for known vulnerabilities | **Not automated** (see limitations) |

## How to repeat

**1. Crypto libraries.** List every crypto import; only `cryptography` / `argon2` and the standard `hashlib` / `hmac`
(Python), `javax.crypto`, `java.security`, `android.security.keystore` and `org.bouncycastle` (Kotlin) should appear:

```bash
git grep -nE "(import|from) +(javax\.crypto|java\.security|android\.security|org\.bouncycastle|cryptography|argon2|hashlib|hmac)" -- desktop android
```

**2–5. Format and crypto behaviour.** `python tests/run_all.py` runs:
`test_vault.py` (tamper, truncation, history gaps, Argon2id upgrade, key wiping) and the Python ⇄ Kotlin compatibility
test (a PBKDF2 vault upgraded by Kotlin, an Argon2id vault from each side). A new vault's `vault.json` must contain
`"kdf": {"name": "argon2id", "m": 65536, "t": 3, "p": 1, …}`.

**6. Local server access.** `tests/test_api.py → test_auth_and_host_check`: requests without the cookie, with a wrong
`Host` header or with a wrong token are refused (403); the cookie is `HttpOnly; SameSite=Strict`. In the code,
`ThreadingHTTPServer(("127.0.0.1", port), …)` in `desktop/app/drivevault.py` binds to the loopback address only.

**7. Script isolation.** Files served from the vault get `Content-Security-Policy: sandbox; default-src 'none'; …`
(except PDF), and the app page has a strict policy (`default-src 'self'; object-src 'none'; base-uri 'none'`). Check with
`curl -sI` against a running demo (`python tools/demo.py`) or read `_stream()` in `drivevault.py`.

**8. No caching.** `test_upload_and_range_requests` checks `Cache-Control: no-store` on file responses. Thumbnails are
canvas images kept as in-memory object URLs (`ui.html`, `makeThumb`) and in an in-memory cache on Android (`Thumbs.kt`);
both are cleared on lock.

**9. Android permissions and flags.** On a built APK:

```bash
aapt2 dump permissions android/app/build/outputs/apk/release/app-release.apk
```

Expected: storage permissions and `ACCESS_NETWORK_STATE` (added by Media3), no `INTERNET`. `MainActivity` sets
`FLAG_SECURE`; `AndroidManifest.xml` has `allowBackup="false"`; both content providers are `exported="false"`.

**10. Repository contents.** Before every push:

```bash
git ls-files
git grep -nIiE "BEGIN [A-Z ]*PRIVATE KEY|ghp_|gho_|github_pat_|AKIA[0-9A-Z]{16}|storePassword *= *\"|password *= *\"[^\"]+\""
```

Expected matches: the fictional passwords in `tests/dvserver.py` and `tools/demo.py`, and this command line itself. `.gitignore` excludes keystores (`*.jks`,
`*.keystore`, `android/signing/`), `local.properties`, APKs, vault folders and logs. Screenshots come from the fictional
demo (`tools/screenshots.py`) and are reviewed by eye.

## Known limitations

- No independent audit, no fuzzing, no formal threat modelling beyond SECURITY.md.
- **Dependencies:** there is no automated vulnerability scanning (such as Dependabot) yet. The desktop app uses whatever
  `cryptography` version the launcher installs; the Android dependencies are pinned in the Gradle files.
- Key wiping on lock is best effort (Python, the JVM and libraries may keep copies; passwords typed into the UI can't be wiped).
- Argon2id timing on phones is estimated, not measured on a range of devices.
- Desktop scripts and the APK are not code-signed by a publisher that Windows or Google recognises; users should verify
  downloads with `SHA256SUMS.txt`.
