package com.drivevault.core

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The storage a vault lives on. Paths are "/"-separated and relative to this folder ("" = the folder itself).
 * [FileFs] is plain java.io.File; the Android app adds a Storage Access Framework implementation.
 */
interface VaultFs {
    val label: String
    fun sub(path: String): VaultFs
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray
    /** Create or truncate, write, and sync to the device. */
    fun write(path: String, data: ByteArray) { create(path).use { it.write(data) } }
    fun append(path: String, data: ByteArray)
    /** A new (or truncated) file; closing the stream syncs it to the device. */
    fun create(path: String): OutputStream
    /** Rename src over dst (same folder); atomic where the storage allows it. */
    fun replace(src: String, dst: String)
    fun copy(src: String, dst: String) { write(dst, read(src)) }
    fun delete(path: String): Boolean
    fun mkdirs(path: String)
    fun length(path: String): Long
    /** Child names, or null when the folder can't be read (missing, no access, drive not ready). */
    fun list(path: String): List<String>?
    fun openRandom(path: String): RandomRead
}

interface RandomRead : Closeable {
    val length: Long
    fun readFully(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size)
}

class FileFs(val dir: File) : VaultFs {
    private fun f(p: String) = if (p.isEmpty()) dir else File(dir, p)
    override val label: String get() = dir.absolutePath
    override fun sub(path: String) = FileFs(f(path))
    override fun exists(path: String) = f(path).exists()
    override fun read(path: String) = f(path).readBytes()
    override fun append(path: String, data: ByteArray) {
        FileOutputStream(f(path), true).use { it.write(data); it.flush(); sync(it) }
    }
    override fun create(path: String): OutputStream {
        val fo = FileOutputStream(f(path)); val buf = BufferedOutputStream(fo, 1 shl 20)
        return object : OutputStream() {
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun flush() = buf.flush()
            override fun close() { try { buf.flush(); sync(fo) } finally { fo.close() } }
        }
    }
    override fun replace(src: String, dst: String) {
        try { Files.move(f(src).toPath(), f(dst).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (e: Exception) { Files.move(f(src).toPath(), f(dst).toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    override fun copy(src: String, dst: String) { f(src).copyTo(f(dst), overwrite = true) }
    override fun delete(path: String) = f(path).delete()
    override fun mkdirs(path: String) { val d = f(path); if (!d.isDirectory && !d.mkdirs() && !d.isDirectory) throw IOException("Can't create folder $d") }
    override fun length(path: String) = f(path).length()
    override fun list(path: String): List<String>? = f(path).list()?.toList()
    override fun openRandom(path: String): RandomRead = object : RandomRead {
        val raf = RandomAccessFile(f(path), "r")
        override val length get() = raf.length()
        override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) { raf.seek(pos); raf.readFully(buf, off, len) }
        override fun close() = raf.close()
    }
    private fun sync(o: FileOutputStream) { try { o.fd.sync() } catch (_: Exception) {} }
}
