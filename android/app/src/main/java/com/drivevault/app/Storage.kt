package com.drivevault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.drivevault.core.*
import java.io.File

enum class DriveState { Vault, Empty, NotReady }

/** A folder that holds (or can hold) DriveVault/: a plain path, or a folder picked with the system picker (SAF). */
class Drive(val id: String, val fs: VaultFs, val title: String, val file: File? = null) {
    fun vaultFs() = fs.sub("DriveVault/vault")
    val label: String get() = file?.absolutePath ?: title

    /** Never report Empty (which offers "Create vault") unless the folder can really be read. */
    fun state(): DriveState = try {
        val mount = file?.let { Environment.getExternalStorageState(it) }
        when {
            mount != null && mount != Environment.MEDIA_MOUNTED && mount != Environment.MEDIA_UNKNOWN -> DriveState.NotReady
            fs.exists("DriveVault/vault/vault.json") -> DriveState.Vault
            fs.list("") == null -> DriveState.NotReady
            else -> DriveState.Empty
        }
    } catch (e: Exception) { DriveState.NotReady }
}

data class Place(val drive: Drive, val removable: Boolean, val state: DriveState, val free: Long, val total: Long)

object Storage {
    fun hasAllFilesAccess(): Boolean =
        try { if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true } catch (e: Exception) { false }

    fun fileDrive(f: File, title: String = f.name.ifEmpty { f.absolutePath }) = Drive("file:" + f.absolutePath, FileFs(f), title, f)
    fun safDrive(ctx: Context, tree: Uri) =
        Drive("saf:$tree", SafFs(ctx, tree), (try { DocumentFile.fromTreeUri(ctx, tree)?.name } catch (e: Exception) { null }) ?: "Picked drive")

    /** Reverse of Drive.id. Older versions stored a bare path. */
    fun driveFromId(ctx: Context, id: String): Drive? = when {
        id.startsWith("saf:") -> Uri.parse(id.removePrefix("saf:")).let { u ->
            if (ctx.contentResolver.persistedUriPermissions.any { it.uri == u }) safDrive(ctx, u) else null }
        else -> fileDrive(File(id.removePrefix("file:")))
    }

    /** System folder picker, opened at the first removable volume when Android allows it. Grants lasting access. */
    fun pickDriveIntent(ctx: Context): Intent {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val vol = if (Build.VERSION.SDK_INT >= 29) sm.storageVolumes.firstOrNull { it.isRemovable } else null
        val i = if (Build.VERSION.SDK_INT >= 29 && vol != null) vol.createOpenDocumentTreeIntent() else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun rememberPicked(ctx: Context, tree: Uri): Drive {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return safDrive(ctx, tree)
    }

    /** Storage volumes: phone storage, SD card, USB drives (USB-C / OTG). Volumes that aren't mounted yet are listed too. */
    fun volumes(ctx: Context): List<Pair<File, String>> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val out = ArrayList<Pair<File, String>>()
        if (Build.VERSION.SDK_INT >= 30) {
            for (v in sm.storageVolumes) {
                val d = v.directory ?: v.uuid?.let { File("/storage/$it") } ?: continue
                out.add(d to (v.getDescription(ctx) ?: d.name))
            }
        } else {
            for (f in ctx.getExternalFilesDirs(null)) {
                if (f == null) continue
                val root = f.absolutePath.substringBefore("/Android/")
                val v = sm.getStorageVolume(f)
                out.add(File(root) to (v?.getDescription(ctx) ?: root))
            }
        }
        return out
    }

    private fun hasVault(b: File) = File(b, "DriveVault/vault/vault.json").isFile

    /** Every place that already holds a vault (a few folders deep), every volume, and every drive picked with the system picker. */
    fun places(ctx: Context): List<Place> {
        val out = LinkedHashMap<String, Place>()
        if (hasAllFilesAccess()) for ((root, title) in volumes(ctx)) {
            val removable = !root.absolutePath.startsWith(Environment.getExternalStorageDirectory().absolutePath)
            fun add(b: File, t: String) {
                val st = try { StatFs(b.absolutePath) } catch (e: Exception) { null }
                out.getOrPut(b.absolutePath) { val d = fileDrive(b, t); Place(d, removable, d.state(), st?.availableBytes ?: 0, st?.totalBytes ?: 0) }
            }
            var budget = 3000
            fun walk(d: File, depth: Int) {
                if (hasVault(d)) add(d, if (d == root) title else "$title › ${d.relativeTo(root).path}")
                if (depth >= 3 || budget <= 0) return
                val kids = try { d.listFiles() } catch (e: Exception) { null } ?: return
                for (k in kids) {
                    if (--budget <= 0) return
                    if (k.isDirectory && !k.name.startsWith(".") && k.name != "Android" && k.name != "DriveVault") walk(k, depth + 1)
                }
            }
            walk(root, 0)
            add(root, title)
        }
        for (p in ctx.contentResolver.persistedUriPermissions) {
            if (!p.isReadPermission || !p.isWritePermission) continue
            val d = try { safDrive(ctx, p.uri) } catch (e: Exception) { continue }
            out.getOrPut(d.id) { Place(d, true, d.state(), 0, 0) }
        }
        return out.values.sortedWith(compareByDescending<Place> { it.state == DriveState.Vault }.thenByDescending { it.removable })
    }

    fun findBase(path: String): File? {
        var f: File? = File(path.trim())
        repeat(3) { val x = f ?: return null; if (hasVault(x)) return x; f = x.parentFile }
        return null
    }

    /** Copy the Windows/Linux app onto the drive so the vault also opens on computers. */
    fun installDesktopApp(ctx: Context, drive: Drive) {
        val fs = drive.fs
        fun copy(asset: String, dest: String) {
            if (asset.endsWith(".txt") && fs.exists(dest)) return
            fs.mkdirs(dest.substringBeforeLast('/', ""))
            ctx.assets.open(asset).use { i -> fs.create(dest).use { o -> i.copyTo(o) } }
        }
        val list = ctx.assets.list("desktop") ?: return
        for (n in list) {
            when {
                n == "app" -> for (a in ctx.assets.list("desktop/app") ?: emptyArray()) copy("desktop/app/$a", "DriveVault/app/$a")
                n.startsWith("app/") -> copy("desktop/$n", "DriveVault/$n")
                else -> copy("desktop/$n", n)
            }
        }
    }

    fun downloadsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    // ---------------- importing (encrypting) files picked on the phone
    data class Src(val name: String, val size: Long, val mtime: Long?, val open: () -> java.io.InputStream, val folders: List<String>)

    fun fromUris(ctx: Context, uris: List<Uri>): List<Src> = uris.mapNotNull { u ->
        var name = "file"; var size = -1L
        ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        Src(name, maxOf(0, size), null, { ctx.contentResolver.openInputStream(u)!! }, emptyList())
    }

    fun fromTree(ctx: Context, tree: Uri): List<Src> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = ArrayList<Src>()
        fun walk(d: DocumentFile, path: List<String>) {
            for (f in d.listFiles()) {
                if (f.isDirectory) walk(f, path + (f.name ?: "Folder"))
                else out.add(Src(f.name ?: "file", f.length(), f.lastModified().takeIf { it > 0 }, { ctx.contentResolver.openInputStream(f.uri)!! }, path))
            }
            if (d.listFiles().isEmpty()) out.add(Src("", 0, null, { java.io.ByteArrayInputStream(ByteArray(0)) }, path))   // keep empty folders
        }
        walk(root, listOf(root.name ?: "Folder"))
        return out
    }

    fun import(v: Vault, parent: String, srcs: List<Src>, job: Job) {
        for (s in srcs) {
            val dest = if (s.folders.isNotEmpty()) v.mkdirs(parent, s.folders) else parent
            if (s.name.isEmpty()) continue
            val id = s.open().use { v.addFile(dest, s.name, it, s.mtime, progress = { n -> job.add(n) }) }
            Session.logItem("encrypted", id, "note" to "added on phone")
        }
        v.saveIndex()
    }
}
