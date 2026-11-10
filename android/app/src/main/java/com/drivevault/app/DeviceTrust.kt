package com.drivevault.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.drivevault.core.Crypto
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * "Trusted phone" recovery: a random device key unlocks a copy of the vault's master key.
 * The device key is stored on this phone only, encrypted by an Android Keystore key that needs the
 * phone's fingerprint / PIN (user authentication) to be used.
 */
object DeviceTrust {
    private const val ALIAS = "drivevault-device-v1"

    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences("dv", Context.MODE_PRIVATE)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
    }

    fun secure(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(60, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(60)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    /** Call right after the user confirmed their screen lock. Returns the new device key (to seal into the vault). */
    fun createAndStore(ctx: Context, vaultId: String): ByteArray {
        val dk = Crypto.random(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val blob = c.iv + c.doFinal(dk)
        ctx.getSharedPreferences("dv", Context.MODE_PRIVATE).edit().putString("dk_$vaultId", Crypto.b64(blob)).apply()
        return dk
    }

    fun has(ctx: Context, vaultId: String?) = vaultId != null && ctx.getSharedPreferences("dv", Context.MODE_PRIVATE).contains("dk_$vaultId")

    /** Call right after the user confirmed their screen lock. */
    fun load(ctx: Context, vaultId: String): ByteArray {
        val blob = Crypto.unb64(ctx.getSharedPreferences("dv", Context.MODE_PRIVATE).getString("dk_$vaultId", null) ?: error("no device key"))
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }

    fun forget(ctx: Context, vaultId: String) = ctx.getSharedPreferences("dv", Context.MODE_PRIVATE).edit().remove("dk_$vaultId").apply()
}
