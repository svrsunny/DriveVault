package com.drivevault.core

import kotlinx.serialization.json.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** DriveVault format v1 — must stay byte-compatible with the desktop app (see FORMAT.md). */

open class VaultException(msg: String) : Exception(msg)
class WrongPassword(msg: String) : VaultException(msg)

typealias Entry = MutableMap<String, JsonElement>

const val ROOT = "root"
const val TRASH = "trash"
const val CHUNK = 1 shl 20
const val TAG = 16
val MAGIC = "DVB1".toByteArray()
var ITERATIONS = 600_000            // PBKDF2, only for vaults made before 1.8
var ARGON2_MEMORY_KIB = 65_536      // password KDF since 1.8 (see SECURITY.md for why these values)
var ARGON2_ITERATIONS = 3
var ARGON2_LANES = 1
var KEY_ITERATIONS = 200_000
var QUESTION_ITERATIONS = 1_000_000
const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private val AAD_MASTER = "drivevault/v1/masterkey".toByteArray()
private val AAD_KEY = "drivevault/v1/recovery-key".toByteArray()
private val AAD_QUESTIONS = "drivevault/v1/recovery-questions".toByteArray()
private val AAD_INDEX = "drivevault/v1/index".toByteArray()
private val AAD_HISTORY = "drivevault/v1/history".toByteArray()
private val AAD_DEVICE = "drivevault/v1/device".toByteArray()
private const val FORBIDDEN = "<>:\"/\\|?*"

object Crypto {
    val rng = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s.trim())
    fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(msg) }

    /** PBKDF2-HMAC-SHA256 over the exact UTF-8 bytes (same as Python hashlib.pbkdf2_hmac). */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, len: Int = 32): ByteArray {
        if (password.isEmpty()) throw WrongPassword("Wrong password.")
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(len); var block = 1; var off = 0
        while (off < len) {
            mac.update(salt); mac.update(ByteBuffer.allocate(4).putInt(block).array())
            var u = mac.doFinal(); val t = u.copyOf()
            for (i in 1 until iterations) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            val n = minOf(t.size, len - off); System.arraycopy(t, 0, out, off, n); off += n; block++
        }
        return out
    }

    fun argon2id(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int, lanes: Int, len: Int = 32): ByteArray {
        val g = Argon2BytesGenerator()
        g.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt).withMemoryAsKB(memoryKiB).withIterations(iterations).withParallelism(lanes).build())
        return ByteArray(len).also { g.generateBytes(password, it) }
    }

    /** Key-encryption key for the password, from the "kdf" object in vault.json. */
    fun passwordKek(kdf: JsonObject, password: String): ByteArray {
        val pw = password.toByteArray(Charsets.UTF_8)
        if (pw.isEmpty()) throw WrongPassword("Wrong password.")
        val salt = unb64(kdf["salt"]!!.jsonPrimitive.content)
        fun n(k: String) = kdf[k]!!.jsonPrimitive.int
        return try {
            when (kdf["name"]?.jsonPrimitive?.content) {
                "argon2id" -> argon2id(pw, salt, n("m"), n("t"), n("p"))
                "pbkdf2-sha256" -> pbkdf2(pw, salt, n("iterations"))
                else -> throw VaultException("This vault was made by a newer DriveVault. Update DriveVault to open it.")
            }
        } finally { pw.fill(0) }
    }

    fun newPasswordKdf(): JsonObject = buildJsonObject {
        put("name", "argon2id"); put("m", ARGON2_MEMORY_KIB); put("t", ARGON2_ITERATIONS); put("p", ARGON2_LANES); put("salt", b64(random(16)))
    }

    fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    /** Returns null when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray? =
        try { gcm(false, key, nonce, data, aad) } catch (e: AEADBadTagException) { null } catch (e: javax.crypto.BadPaddingException) { null }

    fun chunkNonce(i: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(i).array()
    fun chunkAad(i: Long, final: Boolean): ByteArray =
        ByteBuffer.allocate(13).put(MAGIC).putLong(i).put(if (final) 1 else 0).array()
    fun nchunks(size: Long): Long = maxOf(1L, (size + CHUNK - 1) / CHUNK)
}

fun normalizeAnswer(a: String): String {
    val s = Normalizer.normalize(a, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    // Python: re.sub(r"[\W_]+", " ") — word chars are Unicode letters (L*) and numbers (N*), underscore is a separator
    val sb = StringBuilder(); var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val word = when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
            Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
        if (word) sb.appendCodePoint(cp) else sb.append(' ')
        i += Character.charCount(cp)
    }
    return sb.toString().trim().split(Regex(" +")).filter { it.isNotEmpty() }.joinToString(" ")
}

fun normalizeKey(k: String) = k.uppercase(java.util.Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }

fun newRecoveryKey(): String {
    val raw = (1..25).map { KEY_ALPHABET[Crypto.rng.nextInt(KEY_ALPHABET.length)] }.joinToString("")
    return raw.chunked(5).joinToString("-")
}

fun cleanName(name: String?): String {
    val n = (name ?: "").trim().trimEnd('.')
    if (n.isEmpty() || n == "." || n == ".." || n.length > 255 || n.any { it in FORBIDDEN || it.code < 32 })
        throw VaultException("Invalid name. Avoid  < > : \" / \\ | ? *")
    return n
}

private fun readFull(src: InputStream, n: Int): ByteArray {
    val buf = ByteArray(n); var off = 0
    while (off < n) { val r = src.read(buf, off, n - off); if (r < 0) break; off += r }
    return if (off == n) buf else buf.copyOf(off)
}

// ---- Entry helpers
val Entry.parent get() = (this["p"] as? JsonPrimitive)?.contentOrNull ?: ROOT
val Entry.name get() = (this["n"] as JsonPrimitive).content
val Entry.isDir get() = (this["t"] as? JsonPrimitive)?.content == "d"
val Entry.fileSize get() = (this["s"] as? JsonPrimitive)?.longOrNull ?: 0L
val Entry.mtime get() = (this["m"] as? JsonPrimitive)?.longOrNull ?: 0L
val Entry.blob get() = (this["b"] as? JsonPrimitive)?.contentOrNull
val Entry.order get() = (this["o"] as? JsonPrimitive)?.doubleOrNull
val Entry.deletedAt get() = (this["x"] as? JsonPrimitive)?.longOrNull
val Entry.origParent get() = (this["op"] as? JsonPrimitive)?.contentOrNull

data class Item(val id: String, val e: Entry) {
    val name get() = e.name; val isDir get() = e.isDir; val size get() = e.fileSize; val mtime get() = e.mtime
    val order get() = e.order; val deletedAt get() = e.deletedAt
}

class Vault(val fs: VaultFs) {
    constructor(root: File) : this(FileFs(root))
    private val lock = Any()
    var master: ByteArray? = null; private set
    private var indexKey: ByteArray? = null
    private var contentKey: ByteArray? = null
    var entries = LinkedHashMap<String, Entry>(); private set
    private var seq = 0L
    private var indexH = 0L
    private var histSeq: Long? = null

    val exists get() = fs.exists("vault.json")
    val unlocked get() = master != null
    val vaultId: String? get() = try { readMeta()["id"]?.jsonPrimitive?.content } catch (e: Exception) { null }

    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    private fun clear() {
        master?.fill(0); indexKey?.fill(0); contentKey?.fill(0)   // best effort: the JVM may have copied them
        master = null; indexKey = null; contentKey = null; entries = LinkedHashMap(); seq = 0; indexH = 0; histSeq = null
    }
    private fun need() { if (!unlocked) throw VaultException("Vault is locked.") }

    // ---------------- header / keys
    fun readMeta(): MutableMap<String, JsonElement> = Json.parseToJsonElement(fs.read("vault.json").toString(Charsets.UTF_8)).jsonObject.toMutableMap()
    private fun writeMeta(meta: Map<String, JsonElement>) {
        fs.write("vault.json.tmp", prettyJson(JsonObject(meta)).toByteArray())
        if (exists) fs.copy("vault.json", "vault.json.bak")
        fs.replace("vault.json.tmp", "vault.json")
    }

    /** (Re-)wrap the master key under a password. Always Argon2id, so a password change upgrades older PBKDF2 vaults. */
    private fun wrap(meta: MutableMap<String, JsonElement>, master: ByteArray, password: String) {
        val kdf = Crypto.newPasswordKdf(); val nonce = Crypto.random(12)
        val kek = Crypto.passwordKek(kdf, password)
        try {
            meta["kdf"] = kdf
            meta["key"] = buildJsonObject { put("nonce", Crypto.b64(nonce)); put("ct", Crypto.b64(Crypto.gcm(true, kek, nonce, master, AAD_MASTER))) }
        } finally { kek.fill(0) }
    }

    private fun unwrap(meta: Map<String, JsonElement>, password: String): ByteArray {
        if (meta["format"]?.jsonPrimitive?.content != "drivevault" || meta["version"]?.jsonPrimitive?.int != 1)
            throw VaultException("Unsupported vault format.")
        val key = meta["key"]!!.jsonObject
        val kek = Crypto.passwordKek(meta["kdf"]!!.jsonObject, password)
        try {
            return Crypto.open(kek, Crypto.unb64(key["nonce"]!!.jsonPrimitive.content), Crypto.unb64(key["ct"]!!.jsonPrimitive.content), AAD_MASTER)
                ?: throw WrongPassword("Wrong password.")
        } finally { kek.fill(0) }
    }

    /** "argon2id", or "pbkdf2-sha256" for a vault whose password hasn't been changed since before 1.8. */
    fun kdfName(): String? = try { readMeta()["kdf"]?.jsonObject?.get("name")?.jsonPrimitive?.content } catch (e: Exception) { null }

    private fun setKeys(m: ByteArray) {
        master = m
        indexKey = Crypto.hmac(m, "drivevault/v1/index-key".toByteArray())
        contentKey = Crypto.hmac(m, "drivevault/v1/content-key".toByteArray())
    }

    fun create(password: String) = synchronized(lock) {
        if (exists) throw VaultException("A vault already exists here.")
        if (fs.exists("index.dvx") || fs.exists("index.dvx.bak"))
            throw VaultException("This folder holds vault data but its vault.json is missing or unreadable. Not creating a new vault over it.")
        if (password.length < 8) throw VaultException("Use a password of at least 8 characters.")
        fs.mkdirs("blobs")
        val m = Crypto.random(32)
        val meta = linkedMapOf<String, JsonElement>("format" to JsonPrimitive("drivevault"), "version" to JsonPrimitive(1),
            "id" to JsonPrimitive(UUID.randomUUID().toString()))
        wrap(meta, m, password)
        fs.write("vault.json", prettyJson(JsonObject(meta)).toByteArray())
        setKeys(m); entries = LinkedHashMap(); seq = 0
        saveIndex()
    }

    fun unlock(password: String) = synchronized(lock) {
        if (!exists) throw VaultException("No vault found on this drive.")
        val m = unwrap(readMeta(), password)
        setKeys(m); loadIndex()
    }

    fun lockNow() = synchronized(lock) {
        try { if (master != null && (histSeq ?: 0) > indexH) saveIndex() } catch (_: IOException) {}
        clear()
    }

    fun changePassword(old: String, new: String) = synchronized(lock) {
        if (new.length < 8) throw VaultException("Use a password of at least 8 characters.")
        val meta = readMeta(); val m = unwrap(meta, old); wrap(meta, m, new); writeMeta(meta)
    }

    fun verifyPassword(pw: String) { unwrap(readMeta(), pw) }

    // ---------------- recovery
    private fun seal(secret: String, iters: Int, m: ByteArray, aad: ByteArray): JsonObject {
        val salt = Crypto.random(16); val nonce = Crypto.random(12)
        val kek = Crypto.pbkdf2(secret.toByteArray(Charsets.UTF_8), salt, iters)
        return buildJsonObject {
            put("kdf", buildJsonObject { put("name", "pbkdf2-sha256"); put("iterations", iters); put("salt", Crypto.b64(salt)) })
            put("nonce", Crypto.b64(nonce)); put("ct", Crypto.b64(Crypto.gcm(true, kek, nonce, m, aad)))
        }
    }

    private fun openSlot(slot: JsonObject, secret: String, aad: ByteArray): ByteArray? {
        if (secret.isEmpty()) return null
        val kdf = slot["kdf"]!!.jsonObject
        val kek = Crypto.pbkdf2(secret.toByteArray(Charsets.UTF_8), Crypto.unb64(kdf["salt"]!!.jsonPrimitive.content), kdf["iterations"]!!.jsonPrimitive.int)
        return Crypto.open(kek, Crypto.unb64(slot["nonce"]!!.jsonPrimitive.content), Crypto.unb64(slot["ct"]!!.jsonPrimitive.content), aad)
    }

    data class RecoveryInfo(val key: Boolean, val questions: List<String>?, val device: String?, val deviceId: String?)
    fun recoveryInfo(): RecoveryInfo {
        if (!exists) return RecoveryInfo(false, null, null, null)
        val rec = try { readMeta()["recovery"]?.jsonObject } catch (e: Exception) { null } ?: return RecoveryInfo(false, null, null, null)
        val dev = rec["device"]?.jsonObject
        return RecoveryInfo(rec.containsKey("key"), rec["questions"]?.jsonObject?.get("q")?.jsonArray?.map { it.jsonPrimitive.content },
            dev?.get("name")?.jsonPrimitive?.content, dev?.get("did")?.jsonPrimitive?.content)
    }

    /** Trusted phone: the master key wrapped with a random 32-byte device key that only that phone keeps (in its keystore). */
    fun setDevice(password: String, deviceKey: ByteArray, deviceName: String, deviceId: String) = updateRecovery(password) {
        val nonce = Crypto.random(12)
        it["device"] = buildJsonObject {
            put("name", deviceName); put("did", deviceId); put("added", System.currentTimeMillis())
            put("nonce", Crypto.b64(nonce)); put("ct", Crypto.b64(Crypto.gcm(true, deviceKey, nonce, master!!, AAD_DEVICE)))
        }
    }

    fun recoverWithDevice(deviceKey: ByteArray, newPassword: String) = synchronized(lock) {
        if (newPassword.length < 8) throw VaultException("Use a new password of at least 8 characters.")
        val meta = readMeta()
        val slot = meta["recovery"]?.jsonObject?.get("device")?.jsonObject ?: throw VaultException("No trusted phone is set up for this vault.")
        val m = Crypto.open(deviceKey, Crypto.unb64(slot["nonce"]!!.jsonPrimitive.content), Crypto.unb64(slot["ct"]!!.jsonPrimitive.content), AAD_DEVICE)
            ?: throw WrongPassword("This phone is not the trusted phone for this vault.")
        wrap(meta, m, newPassword); writeMeta(meta); setKeys(m); loadIndex()
    }

    private fun updateRecovery(password: String, f: (MutableMap<String, JsonElement>) -> Unit) = synchronized(lock) {
        need(); val meta = readMeta(); unwrap(meta, password)
        val rec = meta["recovery"]?.jsonObject?.toMutableMap() ?: linkedMapOf()
        f(rec); meta["recovery"] = JsonObject(rec); writeMeta(meta)
    }

    fun setRecoveryKey(password: String): String {
        val key = newRecoveryKey()
        updateRecovery(password) { it["key"] = seal(normalizeKey(key), KEY_ITERATIONS, master!!, AAD_KEY) }
        return key
    }

    fun setQuestions(password: String, questions: List<String>, answers: List<String>) {
        val qs = questions.map { it.trim() }; val ans = answers.map { normalizeAnswer(it) }
        if (qs.size != 3 || ans.size != 3 || qs.any { it.isEmpty() }) throw VaultException("Choose three questions.")
        if (qs.map { it.lowercase() }.toSet().size != 3) throw VaultException("Pick three different questions.")
        if (ans.any { it.length < 3 }) throw VaultException("Each answer needs at least 3 letters or numbers.")
        updateRecovery(password) {
            val slot = seal(ans.joinToString("\u001f"), QUESTION_ITERATIONS, master!!, AAD_QUESTIONS).toMutableMap()
            slot["q"] = JsonArray(qs.map { q -> JsonPrimitive(q) })
            it["questions"] = JsonObject(slot)
        }
    }

    fun removeRecovery(password: String, kind: String) = updateRecovery(password) { it.remove(kind) }

    /** kind = "key" (secret = key) or "questions" (secret = answers). Resets the password and unlocks. */
    fun recover(kind: String, key: String?, answers: List<String>?, newPassword: String) = synchronized(lock) {
        if (newPassword.length < 8) throw VaultException("Use a new password of at least 8 characters.")
        val meta = readMeta()
        val slot = meta["recovery"]?.jsonObject?.get(kind)?.jsonObject ?: throw VaultException("This recovery method is not set up.")
        val m = if (kind == "key") openSlot(slot, normalizeKey(key ?: ""), AAD_KEY)
                else openSlot(slot, (answers ?: emptyList()).joinToString("\u001f") { normalizeAnswer(it) }, AAD_QUESTIONS)
        m ?: throw WrongPassword(if (kind == "key") "That recovery key is not right." else "One or more answers are not right.")
        wrap(meta, m, newPassword); writeMeta(meta); setKeys(m); loadIndex()
    }

    fun settings(): JsonObject = try { readMeta()["settings"]?.jsonObject ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }
    fun setSetting(k: String, v: JsonElement) = synchronized(lock) {
        need(); val meta = readMeta(); val s = settings().toMutableMap(); s[k] = v; meta["settings"] = JsonObject(s); writeMeta(meta)
    }

    // ---------------- index
    private fun decryptIndex(f: String): JsonObject {
        val d = fs.read(f)
        val pt = Crypto.open(indexKey!!, d.copyOfRange(0, 12), d.copyOfRange(12, d.size), AAD_INDEX) ?: throw IOException("bad index")
        return Json.parseToJsonElement(pt.toString(Charsets.UTF_8)).jsonObject
    }

    private fun loadIndex() {
        var best: JsonObject? = null
        for (f in listOf("index.dvx", "index.dvx.bak")) {
            try { val o = decryptIndex(f); if (best == null || (o["seq"]?.jsonPrimitive?.long ?: 0) > (best["seq"]?.jsonPrimitive?.long ?: 0)) best = o }
            catch (_: Exception) {}
        }
        val b = best ?: throw VaultException("Vault index is missing or damaged.")
        val map = LinkedHashMap<String, Entry>()
        b["entries"]!!.jsonObject.forEach { (k, v) -> map[k] = v.jsonObject.toMutableMap() }
        entries = map; seq = b["seq"]?.jsonPrimitive?.long ?: 0; indexH = b["h"]?.jsonPrimitive?.long ?: 0
    }

    fun saveIndex() = synchronized(lock) {
        seq += 1
        if (histSeq == null && master != null) histSeq = histLastSeq()
        indexH = maxOf(indexH, histSeq ?: 0)
        val obj = buildJsonObject {
            put("v", 1); put("seq", seq); put("h", indexH)
            put("entries", JsonObject(entries.mapValues { JsonObject(it.value) }))
        }
        val nonce = Crypto.random(12)
        val data = nonce + Crypto.gcm(true, indexKey!!, nonce, Json.encodeToString(JsonObject.serializer(), obj).toByteArray(), AAD_INDEX)
        fs.write("index.dvx.tmp", data)
        if (fs.exists("index.dvx")) fs.copy("index.dvx", "index.dvx.bak")
        fs.replace("index.dvx.tmp", "index.dvx")
    }

    // ---------------- tree
    fun get(id: String): Entry { need(); return entries[id] ?: throw VaultException("Item not found.") }
    fun children(parent: String): List<Item> { need(); return entries.filter { it.value.parent == parent }.map { Item(it.key, it.value) } }

    data class Crumb(val id: String, val name: String)
    fun pathOf(id0: String): List<Crumb> {
        var id = id0; val out = ArrayList<Crumb>()
        while (id != ROOT && id != TRASH) { val e = entries[id] ?: throw VaultException("Item not found."); out.add(Crumb(id, e.name)); id = e.parent }
        return listOf(Crumb(id, if (id == ROOT) "Vault" else "Trash")) + out.reversed()
    }
    fun where(id: String): String = try { pathOf(id).joinToString("/") { it.name } } catch (e: Exception) { "" }

    fun inTrash(id0: String): Boolean {
        var id = id0
        while (id != ROOT && id != TRASH) { id = entries[id]?.parent ?: return false }
        return id == TRASH
    }

    fun findChild(parent: String, name: String): String? {
        val low = name.lowercase()
        return entries.entries.firstOrNull { it.value.parent == parent && it.value.name.lowercase() == low }?.key
    }

    fun uniqueName(parent: String, name: String): String {
        if (findChild(parent, name) == null) return name
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot + 1) else name to ""
        var n = 2
        while (true) { val c = if (ext.isNotEmpty()) "$stem ($n).$ext" else "$stem ($n)"; if (findChild(parent, c) == null) return c; n++ }
    }

    private fun checkParent(parent: String) {
        if (parent == TRASH || (parent != ROOT && inTrash(parent))) throw VaultException("Restore the folder from Trash before adding to it.")
        if (parent != ROOT && !get(parent).isDir) throw VaultException("Not a folder.")
    }

    private fun newEntry(parent: String, name: String, dir: Boolean, size: Long, m: Long, blob: String?): Entry =
        linkedMapOf<String, JsonElement>("p" to JsonPrimitive(parent), "n" to JsonPrimitive(name), "t" to JsonPrimitive(if (dir) "d" else "f"),
            "s" to JsonPrimitive(size), "m" to JsonPrimitive(m)).also { if (blob != null) it["b"] = JsonPrimitive(blob) }

    fun mkdir(parent: String, name0: String, save: Boolean = true): String = synchronized(lock) {
        need(); checkParent(parent); val name = cleanName(name0)
        findChild(parent, name)?.let { if (entries[it]!!.isDir) return it; throw VaultException("A file named '$name' already exists.") }
        val id = UUID.randomUUID().toString().replace("-", "")
        entries[id] = newEntry(parent, name, true, 0, System.currentTimeMillis(), null)
        if (save) saveIndex(); id
    }

    fun mkdirs(parent: String, parts: List<String>): String { var p = parent; for (x in parts) p = mkdir(p, x, false); return p }

    fun rename(id: String, name0: String) = synchronized(lock) {
        val e = get(id); val name = cleanName(name0)
        val other = findChild(e.parent, name); if (other != null && other != id) throw VaultException("That name is already used here.")
        e["n"] = JsonPrimitive(name); saveIndex()
    }

    fun move(id: String, newParent: String) = synchronized(lock) {
        val e = get(id); checkParent(newParent)
        var p = newParent
        while (p != ROOT) { if (p == id) throw VaultException("Can't move a folder into itself."); p = entries[p]!!.parent }
        if (findChild(newParent, e.name).let { it != null && it != id }) e["n"] = JsonPrimitive(uniqueName(newParent, e.name))
        e["p"] = JsonPrimitive(newParent); e.remove("op"); e.remove("x"); e.remove("o")
        saveIndex()
    }

    fun delete(ids: List<String>, progress: ((Int) -> Unit)? = null): Int = synchronized(lock) {
        need()
        val doomed = LinkedHashSet<String>(); val blobs = ArrayList<String>(); val stack = ArrayDeque(ids.filter { it in entries })
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); if (!doomed.add(i)) continue
            entries[i]!!.blob?.let { blobs.add(it) }
            entries.filter { it.value.parent == i }.keys.forEach { stack.add(it) }
        }
        doomed.forEach { entries.remove(it) }
        saveIndex()
        for (b in blobs) { try { fs.delete(blobPath(b)) } catch (_: Exception) {}; progress?.invoke(1) }
        doomed.size
    }

    fun trash(ids: List<String>): Int = synchronized(lock) {
        need(); val now = System.currentTimeMillis(); var n = 0
        for (i in ids) { val e = entries[i] ?: continue; if (inTrash(i)) continue
            e["op"] = JsonPrimitive(e.parent); e["p"] = JsonPrimitive(TRASH); e["x"] = JsonPrimitive(now); n++ }
        saveIndex(); n
    }

    fun restore(ids: List<String>): Int = synchronized(lock) {
        need(); var n = 0
        for (i in ids) {
            val e = entries[i] ?: continue; if (e.parent != TRASH) continue
            var dest = e.origParent ?: ROOT
            if (dest != ROOT && (dest !in entries || inTrash(dest))) dest = ROOT
            e["p"] = JsonPrimitive("")
            e["n"] = JsonPrimitive(uniqueName(dest, e.name)); e["p"] = JsonPrimitive(dest)
            e.remove("op"); e.remove("x"); e.remove("o"); n++
        }
        saveIndex(); n
    }

    fun trashCount() = entries.values.count { it.parent == TRASH }
    fun emptyTrashIds() = entries.filter { it.value.parent == TRASH }.keys.toList()

    fun search(q: String, limit: Int = 300): List<Item> {
        need(); val s = q.trim().lowercase()
        return entries.filter { s in it.value.name.lowercase() && !inTrash(it.key) }.map { Item(it.key, it.value) }.take(limit)
    }

    fun reorder(parent: String, ids: List<String>): Int = synchronized(lock) {
        need(); var n = 0
        ids.forEachIndexed { pos, i -> val e = entries[i]; if (e != null && e.parent == parent) { e["o"] = JsonPrimitive(pos); n++ } }
        saveIndex(); n
    }

    fun treeSize(id: String): Pair<Long, Int> {
        val e = get(id); if (!e.isDir) return e.fileSize to 1
        var s = 0L; var f = 0
        for (c in children(id)) { val (a, b) = treeSize(c.id); s += a; f += b }
        return s to f
    }

    // ---------------- blobs
    fun blobPath(b: String) = "blobs/${b.substring(0, 2)}/$b.dvb"
    private fun fileKey(b: String) = Crypto.hmac(contentKey!!, hexToBytes(b))

    fun writeBlob(src: InputStream, progress: ((Long) -> Unit)? = null): Pair<String, Long> {
        need()
        val blob = Crypto.random(16).joinToString("") { "%02x".format(it) }
        val path = blobPath(blob); fs.mkdirs("blobs/${blob.substring(0, 2)}")
        val tmp = "$path.tmp"; val key = fileKey(blob)
        var total = 0L; var i = 0L
        try {
            fs.create(tmp).use { bout ->
                bout.write(MAGIC)
                var cur = readFull(src, CHUNK)
                while (true) {
                    val nxt = if (cur.size == CHUNK) readFull(src, CHUNK) else ByteArray(0)
                    val fin = nxt.isEmpty()
                    bout.write(Crypto.gcm(true, key, Crypto.chunkNonce(i), cur, Crypto.chunkAad(i, fin)))
                    total += cur.size; i++
                    progress?.invoke(cur.size.toLong())
                    if (fin) break
                    cur = nxt
                }
            }
            fs.replace(tmp, path)
        } catch (e: Throwable) { try { fs.delete(tmp) } catch (_: Exception) {}; throw e }
        return blob to total
    }

    fun addFile(parent: String, name0: String, src: InputStream, mtime: Long? = null, replaceId: String? = null, progress: ((Long) -> Unit)? = null): String {
        val name = cleanName(name0)
        val (blob, size) = writeBlob(src, progress)
        var old: String? = null; val id: String
        synchronized(lock) {
            val m = mtime ?: System.currentTimeMillis()
            if (replaceId != null && replaceId in entries) {
                val e = entries[replaceId]!!; old = e.blob
                e["s"] = JsonPrimitive(size); e["m"] = JsonPrimitive(m); e["b"] = JsonPrimitive(blob); id = replaceId
            } else {
                checkParent(parent)
                id = UUID.randomUUID().toString().replace("-", "")
                entries[id] = newEntry(parent, uniqueName(parent, name), false, size, m, blob)
            }
            saveIndex()
        }
        old?.let { try { fs.delete(blobPath(it)) } catch (_: Exception) {} }
        return id
    }

    /** Random-access decrypting reader for a file entry (used by viewers, the media player and exports). */
    inner class Reader(val id: String) : Closeable {
        private val e = get(id)
        val size = e.fileSize
        private val key = fileKey(e.blob ?: throw VaultException("Not a file."))
        private val n = Crypto.nchunks(size)
        private val raf = fs.openRandom(blobPath(e.blob!!))
        private var cachedIdx = -1L; private var cached = ByteArray(0)
        init {
            val m = ByteArray(4)
            try { raf.readFully(0, m) } catch (ex: Exception) { raf.close(); throw ex }
            if (!m.contentEquals(MAGIC)) { raf.close(); throw VaultException("File is damaged.") }
        }

        private fun chunk(i: Long): ByteArray {
            if (i == cachedIdx) return cached
            val plen = minOf(CHUNK.toLong(), size - i * CHUNK).toInt()
            val ct = ByteArray(plen + TAG); raf.readFully(4 + i * (CHUNK + TAG), ct)
            cached = Crypto.open(key, Crypto.chunkNonce(i), ct, Crypto.chunkAad(i, i == n - 1))
                ?: throw VaultException("'${e.name}' is damaged or was tampered with.")
            cachedIdx = i; return cached
        }

        /** Read up to len bytes at pos. Returns -1 at end of file. */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= size || len == 0) return if (pos >= size) -1 else 0
            val i = pos / CHUNK; val c = chunk(i); val inChunk = (pos - i * CHUNK).toInt()
            val k = minOf(len, c.size - inChunk); System.arraycopy(c, inChunk, buf, off, k); return k
        }

        fun stream(start: Long = 0): InputStream = object : InputStream() {
            var p = start
            override fun read(): Int { val b = ByteArray(1); val r = read(b, 0, 1); return if (r <= 0) -1 else b[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val r = this@Reader.read(p, b, off, len); if (r > 0) p += r; return r }
        }
        override fun close() = raf.close()
    }

    fun readAll(id: String): ByteArray = Reader(id).use { r -> val out = ByteArrayOutputStream(); r.stream().copyTo(out); out.toByteArray() }

    /** Decrypt a file or folder into dest. Returns map localFile -> entry id. */
    fun export(id: String, dest: File, progress: ((Long) -> Unit)? = null, unique: Boolean = false): Map<File, String> {
        val e = get(id); val out = LinkedHashMap<File, String>()
        var target = File(dest, e.name)
        if (unique && target.exists()) {
            val dot = e.name.lastIndexOf('.'); val stem = if (dot > 0) e.name.substring(0, dot) else e.name; val ext = if (dot > 0) e.name.substring(dot) else ""
            var n = 2; while (File(dest, "$stem ($n)$ext").exists()) n++; target = File(dest, "$stem ($n)$ext")
        }
        if (!e.isDir) {
            Reader(id).use { r ->
                FileOutputStream(target).use { fo ->
                    val buf = ByteArray(CHUNK); var p = 0L
                    while (true) { val k = r.read(p, buf, 0, buf.size); if (k <= 0) break; fo.write(buf, 0, k); p += k; progress?.invoke(k.toLong()) }
                }
            }
            target.setLastModified(e.mtime); out[target] = id
        } else {
            target.mkdirs()
            for (c in children(id)) out.putAll(export(c.id, target, progress))
        }
        return out
    }

    // ---------------- history
    private val historyPath = "history.dvl"
    private fun histKey() = Crypto.hmac(master!!, "drivevault/v1/history-key".toByteArray())

    private fun histLastSeq(): Long {
        if (!fs.exists(historyPath)) return 0
        val lines = fs.openRandom(historyPath).use { raf ->
            val start = maxOf(0L, raf.length - 65536)
            val b = ByteArray((raf.length - start).toInt()); raf.readFully(start, b); String(b).lines().filter { it.isNotBlank() }
        }
        val k = histKey()
        for (line in lines.reversed()) {
            try {
                val raw = Crypto.unb64(line); val s = raw.copyOfRange(0, 8)
                if (Crypto.open(k, raw.copyOfRange(8, 20), raw.copyOfRange(20, raw.size), AAD_HISTORY + s) != null) return ByteBuffer.wrap(s).long
            } catch (_: Exception) {}
        }
        return 0
    }

    fun historyAppend(event: JsonObject) = synchronized(lock) {
        need()
        if (histSeq == null) histSeq = histLastSeq()
        histSeq = histSeq!! + 1
        val s = ByteBuffer.allocate(8).putLong(histSeq!!).array(); val nonce = Crypto.random(12)
        val ct = Crypto.gcm(true, histKey(), nonce, Json.encodeToString(JsonObject.serializer(), event).toByteArray(), AAD_HISTORY + s)
        fs.append(historyPath, (Crypto.b64(s + nonce + ct) + "\n").toByteArray())
    }

    data class History(val events: List<JsonObject>, val problems: List<String>)
    fun historyRead(): History {
        need(); val ev = ArrayList<JsonObject>(); val probs = ArrayList<String>(); var expect = 1L; val k = histKey()
        if (!fs.exists(historyPath)) return History(ev, probs)
        fs.read(historyPath).toString(Charsets.UTF_8).lines().forEachIndexed { idx, line ->
            if (line.isBlank()) return@forEachIndexed
            try {
                val raw = Crypto.unb64(line); val sb = raw.copyOfRange(0, 8); val s = ByteBuffer.wrap(sb).long
                val pt = Crypto.open(k, raw.copyOfRange(8, 20), raw.copyOfRange(20, raw.size), AAD_HISTORY + sb) ?: throw IOException()
                if (s != expect) probs.add(if (s > expect) (if (s - 1 == expect) "Event $expect is missing." else "Events $expect–${s - 1} are missing.") else "Event $s is out of order.")
                expect = s + 1
                ev.add(JsonObject(Json.parseToJsonElement(pt.toString(Charsets.UTF_8)).jsonObject + ("seq" to JsonPrimitive(s))))
            } catch (e: Exception) { probs.add("Line ${idx + 1} is damaged or was altered.") }
        }
        if (expect - 1 < indexH) probs.add("The newest ${indexH - (expect - 1)} event(s) were removed from the end of the history.")
        return History(ev, probs)
    }

    companion object {
        fun hexToBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun prettyJson(o: JsonObject) = Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), o)
    }
}
