package com.drivevault.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.drivevault.core.RandomRead
import com.drivevault.core.VaultFs
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * VaultFs over a folder picked with the Storage Access Framework (for phones where "All files access"
 * can't see USB drives). Paths are resolved child by child and the document ids are cached.
 */
class SafFs private constructor(private val tree: Tree, private val prefix: String) : VaultFs {
    constructor(ctx: Context, treeUri: Uri) : this(Tree(ctx.applicationContext, treeUri), "")

    private class Tree(val ctx: Context, val uri: Uri) {
        val ids = ConcurrentHashMap<String, String>().apply { put("", DocumentsContract.getTreeDocumentId(uri)) }
        val cr get() = ctx.contentResolver
        fun doc(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
    }

    override val label: String get() = tree.uri.toString() + (if (prefix.isEmpty()) "" else "/$prefix")
    private fun full(p: String) = listOf(prefix, p).filter { it.isNotEmpty() }.joinToString("/")
    override fun sub(path: String): VaultFs = SafFs(tree, full(path))

    private fun childId(parentId: String, name: String): String? {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree.uri, parentId)
        tree.cr.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1).equals(name, ignoreCase = true)) return c.getString(0)
        }
        return null
    }

    /** Document id for a full path, or null if it doesn't exist. */
    private fun resolve(fullPath: String): String? {
        tree.ids[fullPath]?.let { return it }
        val parent = fullPath.substringBeforeLast('/', "")
        val pid = resolve(parent) ?: return null
        return childId(pid, fullPath.substringAfterLast('/'))?.also { tree.ids[fullPath] = it }
    }

    private fun forget(fullPath: String) { tree.ids.keys.removeIf { it == fullPath || it.startsWith("$fullPath/") } }

    private fun idOf(p: String) = resolve(full(p)) ?: throw FileNotFoundException(full(p))

    private fun createFile(p: String): String {
        val fp = full(p); resolve(fp)?.let { return it }
        val parent = fp.substringBeforeLast('/', ""); val name = fp.substringAfterLast('/')
        val pid = mkdirsFull(parent)
        val u = DocumentsContract.createDocument(tree.cr, tree.doc(pid), "application/octet-stream", name) ?: throw IOException("Can't create $name")
        val id = DocumentsContract.getDocumentId(u)
        tree.ids[fp] = id
        return id
    }

    private fun mkdirsFull(fp: String): String {
        resolve(fp)?.let { return it }
        val pid = mkdirsFull(fp.substringBeforeLast('/', ""))
        val u = DocumentsContract.createDocument(tree.cr, tree.doc(pid), Document.MIME_TYPE_DIR, fp.substringAfterLast('/'))
            ?: throw IOException("Can't create folder $fp")
        return DocumentsContract.getDocumentId(u).also { tree.ids[fp] = it }
    }

    override fun exists(path: String) = try { resolve(full(path)) != null } catch (e: SecurityException) { false }
    override fun read(path: String): ByteArray =
        (tree.cr.openInputStream(tree.doc(idOf(path))) ?: throw FileNotFoundException(full(path))).use { it.readBytes() }

    override fun create(path: String): OutputStream {
        val pfd = tree.cr.openFileDescriptor(tree.doc(createFile(path)), "rwt") ?: throw IOException("Can't write ${full(path)}")
        val fo = FileOutputStream(pfd.fileDescriptor); val buf = BufferedOutputStream(fo, 1 shl 20)
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun flush() = buf.flush()
            override fun close() { try { buf.flush(); try { fo.fd.sync() } catch (_: Exception) {} } finally { fo.close(); pfd.close() } }
        }
    }

    override fun append(path: String, data: ByteArray) {
        (tree.cr.openFileDescriptor(tree.doc(createFile(path)), "wa") ?: throw IOException("Can't write ${full(path)}")).use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { it.write(data); it.flush(); try { it.fd.sync() } catch (_: Exception) {} }
        }
    }

    /** SAF has no atomic rename-over: delete the target, then rename. index.dvx.bak / vault.json.bak cover a crash in between. */
    override fun replace(src: String, dst: String) {
        val sid = idOf(src); val dfp = full(dst)
        resolve(dfp)?.let { DocumentsContract.deleteDocument(tree.cr, tree.doc(it)); forget(dfp) }
        val u = DocumentsContract.renameDocument(tree.cr, tree.doc(sid), dfp.substringAfterLast('/')) ?: tree.doc(sid)
        forget(full(src)); tree.ids[dfp] = DocumentsContract.getDocumentId(u)
    }

    override fun delete(path: String): Boolean {
        val fp = full(path); val id = resolve(fp) ?: return false
        return DocumentsContract.deleteDocument(tree.cr, tree.doc(id)).also { forget(fp) }
    }

    override fun mkdirs(path: String) { mkdirsFull(full(path)) }

    override fun length(path: String): Long =
        tree.cr.query(tree.doc(idOf(path)), arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L

    override fun list(path: String): List<String>? = try {
        val id = resolve(full(path)) ?: return null
        tree.cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree.uri, id), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> ArrayList<String>().apply { while (c.moveToNext()) add(c.getString(0)) } }
    } catch (e: Exception) { null }

    override fun openRandom(path: String): RandomRead {
        val pfd = tree.cr.openFileDescriptor(tree.doc(idOf(path)), "r") ?: throw FileNotFoundException(full(path))
        val ch = FileInputStream(pfd.fileDescriptor).channel
        return object : RandomRead {
            override val length get() = pfd.statSize
            override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
                val bb = ByteBuffer.wrap(buf, off, len); var p = pos
                while (bb.hasRemaining()) { val n = ch.read(bb, p); if (n < 0) throw EOFException(); p += n }
            }
            override fun close() { ch.close(); pfd.close() }
        }
    }
}
