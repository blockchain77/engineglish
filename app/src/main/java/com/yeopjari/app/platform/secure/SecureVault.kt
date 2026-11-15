package com.yeopjari.app.platform.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 차 안 비밀 금고 — Android Keystore(하드웨어 보안 영역)에 AES-256 키를 두고 학습 기록 파일을 AES-256-GCM 으로 암호화해 저장한다.
 * 키는 기기 밖으로 꺼낼 수 없으므로, 파일이 복사돼도 이 차가 아니면 열 수 없다. 앱은 어떤 데이터도 서버로 보내지 않는다.
 */
class SecureVault(private val context: Context) {
    private val ks: KeyStore? = runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) } }
        .onFailure { Log.w(TAG, "Keystore 사용 불가", it) }.getOrNull()

    val available: Boolean get() = ks != null

    private fun aesKey(): SecretKey {
        (ks?.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        g.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    fun write(name: String, plain: ByteArray): Boolean = runCatching {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = c.iv
        val ct = c.doFinal(plain)
        val bytes = byteArrayOf(iv.size.toByte()) + iv + ct
        // 임시 파일에 다 쓴 뒤 이름을 바꾼다 — 쓰는 도중 전원이 꺼져도 이전 기록이 깨지지 않게
        val target = File(context.filesDir, name)
        val tmp = File(context.filesDir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }.onFailure { Log.w(TAG, "암호화 저장 실패: ${it.javaClass.simpleName}") }.isSuccess

    fun read(name: String): ByteArray? = runCatching {
        val f = File(context.filesDir, name)
        if (!f.exists()) return null
        val b = f.readBytes()
        val n = b[0].toInt()
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, b, 1, n))
        c.doFinal(b, 1 + n, b.size - 1 - n)
    }.onFailure { Log.w(TAG, "복호화 실패: ${it.javaClass.simpleName}") }.getOrNull()

    fun size(name: String): Long = File(context.filesDir, name).let { if (it.exists()) it.length() else 0L }

    fun delete(name: String) {
        File(context.filesDir, name).delete()
        File(context.filesDir, "$name.tmp").delete()
    }

    companion object {
        private const val TAG = "SecureVault"
        private const val PROVIDER = "AndroidKeyStore"
        private const val AES_ALIAS = "yeopjari_personal_vault"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
