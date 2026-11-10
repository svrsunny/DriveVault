# Security

## Reporting a vulnerability

Please report security problems **privately** through GitHub:
[Security → Report a vulnerability](https://github.com/svrsunny/DriveVault/security/advisories/new).
Don't open a public issue for them. Include the version (shown at the bottom of the desktop app, or in Android's app
info), the platform and the steps to reproduce. This is a one-person project, so replies can take a while; there is no
bug bounty.

Supported versions: only the latest release gets fixes.

## Design

| Part | How |
|---|---|
| Files | AES-256-GCM in 1 MiB authenticated chunks with a separate key per file; edited, reordered or truncated data is detected |
| Names, folders, order, trash | An encrypted index (AES-256-GCM) |
| Password → key | Argon2id (64 MiB memory, 3 passes, 1 lane, random salt) unwraps a random 256-bit master key. Vaults made before 1.8 use PBKDF2-HMAC-SHA256 (600,000 iterations) until their password is changed |
| Recovery | Recovery key (about 125 bits), security questions (PBKDF2, 1,000,000 iterations), or a trusted phone (key kept in the Android Keystore behind the phone's screen lock) |
| History | Each entry is encrypted and numbered; missing or edited entries are detected |
| Thumbnails | Made in memory from the decrypted photo, never written to disk, dropped on lock |
| Desktop app | Listens on `127.0.0.1` only, random port, session cookie from a one-time link, `Host` header check, strict Content-Security-Policy |
| Android app | No internet permission, screenshots blocked (`FLAG_SECURE`), app data excluded from backups |

Only well-known crypto libraries are used: Python [`cryptography`](https://cryptography.io) (or `argon2-cffi` for
Argon2id), Java `javax.crypto`, and Bouncy Castle for Argon2id on Android. The byte-level spec is in
[docs/FORMAT.md](docs/FORMAT.md); the checks behind this table are in [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md).

**Why these Argon2id settings:** 64 MiB and 3 passes take about 0.2 s on a desktop PC and are estimated at 1–1.7 s on a
mid-range phone (not measured on a real phone). The memory cost makes guessing on graphics cards expensive. The values
are stored in each vault, so they can be raised later without changing the format.

## What it protects against

Someone who gets the drive (lost, stolen, borrowed) and doesn't know the password or a recovery secret.

It does **not** protect against malware or someone else using the computer or phone while the vault is unlocked there,
and it can't stop someone from deleting or damaging the encrypted files on the drive.

## Limits

- **The password is the weak point.** Use 4–5 random words. Argon2id makes each guess cost 64 MiB of memory, but a short
  or common password can still be found.
- **Security questions** are easier to guess than a password. Prefer the recovery key or a trusted phone.
- **Not hidden:** the number and approximate size of files, the security-question text, and `DriveVault/activity.log`,
  a plain-text log of actions with time, computer name, operating system and user name (file names only if you turn that on).
- "Open in app" and "Copy to computer" write decrypted copies to that computer. Working copies are overwritten and deleted
  on lock, but SSDs and backup tools may keep traces.
- **Keys in memory** are overwritten on lock, but only best effort: Python, the JVM and the crypto libraries may keep
  other copies, and typed passwords can't be wiped.
- **Not independently audited.** There is no backdoor: lose the password and every recovery method and the data is gone.
