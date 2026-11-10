package com.drivevault.app

import android.content.Context
import android.os.Build
import androidx.compose.runtime.*
import com.drivevault.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** A long operation shown with %, speed and time left. */
class Job(val label: String, val total: Long, val unit: String = "bytes") {
    val id = UUID.randomUUID().toString()
    val start = System.currentTimeMillis()
    var done by mutableLongStateOf(0L)
    @Volatile private var pending = 0L
    private var lastPush = 0L
    fun add(n: Long) {
        pending += n
        val now = System.currentTimeMillis()
        if (now - lastPush > 150 || pending + done >= total) { val p = pending; pending = 0; lastPush = now; done += p }
    }
}

enum class Screen { Locations, Lock, Browser, History, Security }

val EVENT_TEXT = mapOf(
    "created" to "VAULT CREATED", "unlocked" to "UNLOCKED", "unlock_failed" to "UNLOCK FAILED", "locked" to "LOCKED",
    "password_changed" to "PASSWORD CHANGED", "password_reset" to "PASSWORD RESET", "recovery_key_created" to "RECOVERY KEY CREATED",
    "recovery_key_removed" to "RECOVERY KEY REMOVED", "questions_set" to "SECURITY QUESTIONS SET", "questions_removed" to "SECURITY QUESTIONS REMOVED",
    "device_added" to "TRUSTED PHONE ADDED", "device_removed" to "TRUSTED PHONE REMOVED",
    "recover_failed" to "RECOVERY FAILED", "encrypted" to "ENCRYPTED (added)", "folder_created" to "FOLDER CREATED", "renamed" to "RENAMED",
    "moved" to "MOVED", "trashed" to "MOVED TO TRASH", "restored" to "RESTORED", "deleted" to "DELETED FOREVER", "trash_emptied" to "TRASH EMPTIED",
    "opened" to "DECRYPTED (opened)", "revealed" to "DECRYPTED (show in folder)", "downloaded" to "DECRYPTED (downloaded)",
    "exported" to "DECRYPTED (copied to computer)", "clipboard" to "DECRYPTED (copied to clipboard)", "reordered" to "CUSTOM ORDER CHANGED",
    "streamed" to "PLAYED (streamed to media player)", "viewed" to "VIEWED", "saved_back" to "RE-ENCRYPTED (edited copy saved)",
    "added_back" to "ENCRYPTED (new file from working folder)", "settings" to "SETTINGS CHANGED", "history_exported" to "HISTORY EXPORTED",
    "shared" to "DECRYPTED (shared to another app)",
)

object Session {
    lateinit var ctx: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val host: String get() = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

    var screen by mutableStateOf(Screen.Locations)
    var drive by mutableStateOf<Drive?>(null)        // folder that holds DriveVault/
    var vault by mutableStateOf<Vault?>(null)
    var unlocked by mutableStateOf(false)
    var version by mutableIntStateOf(0)             // bump to refresh lists after any change
    val jobs = mutableStateListOf<Job>()
    var toast by mutableStateOf<Pair<String, Long>?>(null)
    var failedAttempts = 0
    var autoFocus = true
    @Volatile var awayOk = false      // set while we open a picker / another app, so auto-lock waits longer
    val pendingShares = mutableStateListOf<android.net.Uri>()
    private val recentViews = HashMap<String, Long>()

    val prefs get() = ctx.getSharedPreferences("dv", Context.MODE_PRIVATE)

    fun say(msg: String) { toast = msg to System.currentTimeMillis() }
    fun changed() { version++ }

    fun select(newBase: File) = select(Storage.fileDrive(newBase))

    fun select(d: Drive) {
        if (unlocked) lock("switch")
        drive = d
        vault = Vault(d.vaultFs())
        failedAttempts = 0
        prefs.edit().putString("base", d.id).apply()
        screen = Screen.Lock
    }

    /** DriveVault/ on the drive (activity.log lives there), or null if it isn't there. */
    fun appDir(): VaultFs? = drive?.fs?.takeIf { try { it.exists("DriveVault") } catch (e: Exception) { false } }?.sub("DriveVault")

    // ---------------- working files on the phone (private cache, wiped on lock)
    val workDir: File get() = File(ctx.cacheDir, "open").apply { mkdirs() }
    fun wipeWork() {
        workDir.walkBottomUp().forEach { f ->
            if (f.isFile) try { f.outputStream().use { o -> o.write(ByteArray(minOf(f.length(), 8L shl 20).toInt())) } } catch (_: Exception) {}
            f.delete()
        }
    }

    fun onUnlocked(via: String) {
        unlocked = true
        val n = failedAttempts; failedAttempts = 0
        log("unlocked", "via" to via, "note" to if (n > 0) "$n failed attempt(s) before this unlock" else null)
        screen = Screen.Browser; changed()
    }

    fun lock(why: String = "manual") {
        val v = vault ?: return
        if (v.unlocked) {
            log("locked", "why" to why)
            try { v.lockNow() } catch (_: Exception) {}
        }
        unlocked = false
        VaultProvider.revokeAll()
        Thumbs.clear()
        wipeWork()
        if (screen != Screen.Locations) screen = Screen.Lock
        changed()
    }

    // ---------------- activity: encrypted history + plain activity.log
    private fun human(n: Long): String {
        var x = n.toDouble(); val u = listOf("B", "KB", "MB", "GB", "TB"); var i = 0
        while (x >= 1024 && i < u.size - 1) { x /= 1024; i++ }
        return if (i == 0) "${n} B" else String.format(Locale.US, "%.1f %s", x, u[i])
    }

    private fun namesInPlainLog(v: Vault) = try { v.settings()["plainLogNames"]?.jsonPrimitive?.booleanOrNull == true } catch (e: Exception) { false }

    fun log(ev: String, vararg fields: Pair<String, Any?>) {
        val v = vault ?: return
        val obj = buildJsonObject {
            put("t", System.currentTimeMillis()); put("ev", ev); put("host", host); put("os", "Android"); put("user", "")
            for ((k, x) in fields) when (x) {
                null -> {}
                is String -> if (x.isNotEmpty()) put(k, x)
                is Number -> put(k, x)
                is Boolean -> put(k, x)
                else -> put(k, x.toString())
            }
        }
        if (v.unlocked) try { v.historyAppend(obj) } catch (_: Exception) {}
        try {
            val dir = appDir() ?: return
            if (dir.exists("activity.log") && dir.length("activity.log") > 5L * 1024 * 1024) dir.replace("activity.log", "activity.1.log")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            val names = namesInPlainLog(v)
            val bits = ArrayList<String>()
            fun f(k: String) = obj[k]?.jsonPrimitive?.contentOrNull
            if (names) {
                f("name")?.let { bits.add("\"$it\"") }; f("from")?.let { bits.add("from \"$it\"") }
                f("path")?.let { bits.add("in \"$it\"") }; f("dest")?.let { bits.add("to \"$it\"") }
            } else if (f("name") != null) bits.add(if (f("kind") == "d") "1 folder" else "1 item")
            f("files")?.let { bits.add("$it file(s)") }
            obj["size"]?.jsonPrimitive?.longOrNull?.let { if (it > 0) bits.add("(${human(it)})") }
            for (k in listOf("via", "why", "note")) f(k)?.let { bits.add("[$it]") }
            val line = "$ts  $host (Android)  ${EVENT_TEXT[ev] ?: ev.uppercase()}  ${bits.joinToString(" ")}".trimEnd()
            dir.append("activity.log", (line + "\n").toByteArray())
        } catch (_: Exception) {}
    }

    fun logItem(ev: String, id: String, vararg extra: Pair<String, Any?>) {
        val v = vault ?: return
        val e = v.entries[id] ?: return log(ev, *extra)
        val (size, files) = if (e.isDir) v.treeSize(id) else e.fileSize to 1
        log(ev, "name" to e.name, "path" to v.where(e.parent), "kind" to if (e.isDir) "d" else "f", "size" to size,
            "files" to if (e.isDir) files else null, *extra)
    }

    fun logView(id: String) {
        val now = System.currentTimeMillis()
        if (now - (recentViews[id] ?: 0) > 120_000) { recentViews[id] = now; logItem("viewed", id) }
    }

    /** A message people can act on, for any error from vault work. Touches the drive, so call it off the main thread. */
    fun friendlyError(e: Throwable): String {
        if (e is VaultException) return e.message ?: "Error"
        if (e is SecurityException) return "Android no longer lets DriveVault use this drive. Lock, tap Change and pick the drive again."
        val msg = e.message ?: ""
        val errno = generateSequence(e) { it.cause }.filterIsInstance<android.system.ErrnoException>().firstOrNull()?.errno
        if (errno == android.system.OsConstants.ENOSPC || "ENOSPC" in msg || "No space left" in msg) return "Not enough space on the drive. Free some space and try again."
        if (e is java.io.IOException) {
            if (drive?.state() == DriveState.NotReady) return "The drive was removed or stopped responding. Plug it back in, then lock and unlock the vault."
            if (errno == android.system.OsConstants.EROFS || "EROFS" in msg || "Read-only" in msg) return "The drive is read-only. Unplug it, plug it back in and try again."
            return "Drive error: $msg. Is the drive still connected?"
        }
        return "Something went wrong (${e.javaClass.simpleName}${if (msg.isNotEmpty()) ": $msg" else ""})."
    }

    // ---------------- background work with progress
    fun <T> work(label: String, total: Long, unit: String = "bytes", body: suspend (Job) -> T, done: (T) -> Unit = {}) {
        val j = Job(label, total, unit); jobs.add(j)
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { body(j) }
                done(r)
            } catch (e: Exception) { say(withContext(Dispatchers.IO) { friendlyError(e) }) }
            finally { jobs.remove(j); changed() }
        }
    }

    fun io(body: suspend () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { body() } }
            catch (e: Exception) { say(withContext(Dispatchers.IO) { friendlyError(e) }) }
            finally { changed() }
        }
    }
}
