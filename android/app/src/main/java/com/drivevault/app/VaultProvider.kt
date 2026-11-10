package com.drivevault.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.drivevault.core.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets other apps (VLC, PDF readers, share targets...) read a vault file through a content:// link.
 * Bytes are decrypted on demand as the other app reads/seeks: no decrypted copy is written anywhere.
 * Links stop working as soon as the vault is locked.
 */
class VaultProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.drivevault.app.vault"
        private val tokens = ConcurrentHashMap<String, String>()   // token -> entry id
        private val thread by lazy { HandlerThread("vault-fd").apply { start() } }
        fun uriFor(id: String, name: String): Uri {
            val t = UUID.randomUUID().toString().replace("-", "")
            tokens[t] = id
            return Uri.Builder().scheme("content").authority(AUTH).appendPath(t).appendPath(name).build()
        }
        fun revokeAll() = tokens.clear()
        fun mime(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }

    private fun entry(uri: Uri): Pair<Vault, String>? {
        val id = tokens[uri.pathSegments.firstOrNull() ?: return null] ?: return null
        val v = Session.vault ?: return null
        if (!v.unlocked || id !in v.entries) return null
        return v to id
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = mime(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val (v, id) = entry(uri) ?: return null
        val e = v.entries[id]!!
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) e.fileSize else if (it == OpenableColumns.DISPLAY_NAME) e.name else null }) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val (v, id) = entry(uri) ?: throw java.io.FileNotFoundException("Vault is locked")
        val reader = v.Reader(id)
        val sm = context!!.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize() = reader.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (entry(uri) == null) throw ErrnoException("read", OsConstants.EACCES)
                var got = 0
                try {
                    while (got < size) { val k = reader.read(offset + got, data, got, size - got); if (k <= 0) break; got += k }
                } catch (e: Exception) { throw ErrnoException("read", OsConstants.EIO) }
                return got
            }
            override fun onRelease() { try { reader.close() } catch (_: Exception) {} }
        }, Handler(thread.looper))
    }

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
}
