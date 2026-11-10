package com.drivevault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.drivevault.core.Vault
import com.drivevault.core.blob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Photo thumbnails for the grid view: decoded straight from the encrypted file into memory, never written anywhere, dropped on lock. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(32 shl 20) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val gate = Semaphore(3)

    /** Keyed by blob too, so a file whose content was replaced gets a fresh thumbnail. */
    fun key(v: Vault, id: String) = "$id:${v.entries[id]?.blob}"
    fun cached(key: String): Bitmap? = cache.get(key)
    fun clear() = cache.evictAll()

    suspend fun load(v: Vault, id: String, key: String, px: Int = 256): Bitmap? = gate.withPermit {
        cache.get(key)?.let { return@withPermit it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        v.Reader(id).use { r -> BitmapFactory.decodeStream(r.stream(), null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return@withPermit null
        var s = 1; while (minOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        v.Reader(id).use { r -> BitmapFactory.decodeStream(r.stream(), null, BitmapFactory.Options().apply { inSampleSize = s }) }
            ?.also { if (v.unlocked) cache.put(key, it) }
    }
}
