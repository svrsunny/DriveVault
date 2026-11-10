package com.drivevault.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.drivevault.core.Vault

/** Feeds ExoPlayer straight from the encrypted blob (decrypting 1 MB chunks as it plays / seeks). */
@UnstableApi
class VaultDataSource(private val v: Vault, private val id: String) : BaseDataSource(false) {
    private var reader: Vault.Reader? = null
    private var pos = 0L; private var remaining = 0L; private var uri: Uri? = null; private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val r = v.Reader(id); reader = r
        uri = dataSpec.uri; pos = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - pos
        opened = true; transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n; remaining -= n; bytesTransferred(n)
        return n
    }

    override fun getUri() = uri
    override fun close() {
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        if (opened) { opened = false; transferEnded() }
    }
}
