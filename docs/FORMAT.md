# DriveVault format v1

Desktop (Python) and Android (Kotlin) must both follow this file exactly.
Primitives: Argon2id (RFC 9106, v1.3) and PBKDF2-HMAC-SHA256 for passwords, HMAC-SHA256, AES-256-GCM (128-bit tag).
Argon2id comes from `cryptography` (or `argon2-cffi`) in Python and Bouncy Castle on Android; the rest is built in.

## Layout

```
<drive>/DriveVault/vault/
  vault.json            plaintext header (KDF params + wrapped master key)
  index.dvx             encrypted folder tree (names, sizes, blob ids)
  index.dvx.bak         previous index (fallback if index.dvx is damaged)
  blobs/<aa>/<blobid>.dvb   one encrypted blob per file (aa = first 2 hex chars)
  .lock                 runtime only (desktop): an OS file lock is held on it while the vault is unlocked,
                        so a second window can't open the same vault. Not part of the data; readers ignore it.
```

## vault.json

```json
{
  "format": "drivevault", "version": 1,
  "id": "<uuid4>",
  "kdf": {"name": "argon2id", "m": 65536, "t": 3, "p": 1, "salt": "<b64, 16 bytes>"},
  "key": {"nonce": "<b64, 12 bytes>", "ct": "<b64, 48 bytes>"}
}
```

- `kdf.name` selects the password KDF. Readers must support both; an unknown name means "made by a newer DriveVault".
  - `"argon2id"` (since 1.8): `KEK = Argon2id(password UTF-8, salt, memory = m KiB, iterations = t, lanes = p, version 0x13, 32 bytes)`.
    m / t / p are read from the file, so they can change later without a format change.
  - `"pbkdf2-sha256"` (before 1.8): `{"iterations", "salt"}`, `KEK = PBKDF2-HMAC-SHA256(password UTF-8, salt, iterations, 32 bytes)`.
- `master (32 random bytes) = AES-GCM-Decrypt(KEK, nonce, ct, aad="drivevault/v1/masterkey")`
- A wrong password fails the GCM tag check. Changing (or resetting) the password only re-wraps the master key, always with
  a fresh Argon2id `kdf`, so PBKDF2 vaults upgrade on their next password change. (A desktop whose Python has no Argon2id
  falls back to PBKDF2.) An empty password is always wrong.

### Recovery slots (optional, `"recovery"` object in vault.json)

Each slot wraps the same master key with a different secret:

```json
"recovery": {
  "key":       {"kdf": {...200000 iterations...}, "nonce": "..", "ct": ".."},
  "questions": {"q": ["Q1", "Q2", "Q3"], "kdf": {...1000000 iterations...}, "nonce": "..", "ct": ".."}
}
```

- **key**: the secret is the 25-character recovery key (alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`), uppercased, with
  everything except A–Z/0–9 stripped. AAD is `"drivevault/v1/recovery-key"`.
- **questions**: the secret is the 3 answers joined with `\x1f`. Each answer is normalized like this: NFKC, lowercase, every run of
  non-word characters becomes one space, then trimmed. AAD is `"drivevault/v1/recovery-questions"`.
- **device** (trusted Android phone): `{"name", "did", "added", "nonce", "ct"}`. The master key is AES-GCM-encrypted directly with a random
  32-byte device key, AAD `"drivevault/v1/device"`, with no KDF. Only the phone keeps that device key, encrypted by an Android Keystore key
  that needs the phone's screen lock to use.
- A reset re-wraps the master key under the new password. The recovery slots are left as they are.

## Subkeys

```
indexKey   = HMAC-SHA256(master, "drivevault/v1/index-key")
contentKey = HMAC-SHA256(master, "drivevault/v1/content-key")
fileKey    = HMAC-SHA256(contentKey, blobIdBytes)   # blobId = 16 random bytes, stored as 32 hex chars
```

## index.dvx

`nonce (12 random bytes) || AES-GCM(indexKey, nonce, utf8(json), aad="drivevault/v1/index")`

```json
{"v": 1, "seq": 42,
 "entries": {"<entryId>": {"p": "<parentId|root>", "n": "name", "t": "d|f",
                           "s": 1234, "m": 1730000000000, "b": "<blobId, files only>"}}}
```

- The root folder has the id `root` and is not stored in `entries`.
- Trash: a deleted item gets `"p": "trash"`, `"op": <original parent>`, `"x": <deleted ms>`. Its children keep
  their parent links. Restoring moves the item back to `op`, or to `root` if `op` is gone, and drops `op`/`x`.
  "Delete forever" removes the entries and their blobs.
- Custom order: optional `"o"` (number) per entry, the position inside its folder. Folders always come before files.
  Items without `"o"` go after the ordered ones, sorted by name. Moving to another folder, trashing then restoring drops `"o"`.
- Names are unique per folder, compared case-insensitively.
- Each save increments `seq`. On open, the reader takes whichever valid copy (index or .bak) has the higher `seq`.
- Write order: write to `index.dvx.tmp`, fsync, copy the current index to `.bak`, then rename the tmp file over `index.dvx`.
  (Storage without atomic rename, like Android's Storage Access Framework, deletes `index.dvx` first; `.bak` covers a crash.)
- Clean-up on unlock (desktop): `*.tmp` files are deleted. A `.dvb` that neither index copy refers to is deleted only when
  both copies decrypted and the newest is `index.dvx`, so a damaged index never causes data to be deleted.
- A vault is never created in a folder that already has `index.dvx` / `index.dvx.bak` (vault.json missing or unreadable).

## history.dvl (encrypted audit history)

`historyKey = HMAC-SHA256(master, "drivevault/v1/history-key")`. The file is append-only, with one event per line:

```
line = base64( uint64_be(seq) || nonce(12) || AES-GCM(historyKey, nonce, utf8(json), aad="drivevault/v1/history" || uint64_be(seq)) )
event json: {"t": ms, "ev": "encrypted|opened|exported|trashed|...", "name", "path", "kind", "size", "files",
             "from", "dest", "via", "why", "note", "host", "os", "user"}
```

- `seq` starts at 1 and goes up by 1 each event. Readers report gaps (lines removed), lines that fail to decrypt (edited), and
  lines out of order.
- The index stores `"h"` = the highest `seq` at its last save, and the index is saved on every lock. If the history ends below
  `h`, entries were cut off the end.
- `DriveVault/activity.log` (outside the vault) is a plain-text copy for reading without the password. File names appear in it
  only when `vault.json` → `settings.plainLogNames` is true. Failed unlock attempts are written there, and the encrypted
  history notes them at the next unlock.

## Blob (.dvb)

```
"DVB1" (4 bytes) || chunk_0 || chunk_1 || ... || chunk_{n-1}
chunk_i     = AES-GCM(fileKey, nonce_i, plaintext_i, aad_i)   # ciphertext + 16-byte tag
plaintext_i = 1 MiB (1,048,576 bytes) except the last chunk
n           = max(1, ceil(size / 1 MiB))                      # an empty file has 1 empty chunk
nonce_i     = 00 00 00 00 || uint64_be(i)
aad_i       = "DVB1" || uint64_be(i) || (0x01 if i == n-1 else 0x00)
```

The final-chunk flag stops truncation, and the chunk index stops reordering.
Chunk `i` starts at byte offset `4 + i * (1 MiB + 16)`, so readers can jump
straight to any chunk (needed for video seeking).
Blobs are written to `<blobid>.dvb.tmp` and then renamed.
