package com.yeopjari.app.platform.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 차 안 비밀 금고 — Android Keystore(하드웨어 보안 영역)에 키를 두고
 *  - 개인 데이터 파일은 AES-256-GCM으로 암호화해 저장하고
 *  - 서버로 보낼 학습 업데이트는 차량 개인키(ECDSA P-256)로 서명한다.
 * 키는 기기 밖으로 꺼낼 수 없으므로, 파일이 복사돼도 이 차가 아니면 열 수 없다.
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

    private fun signKeys(): KeyPair {
        (ks?.getEntry(SIGN_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return KeyPair(it.certificate.publicKey, it.privateKey) }
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        g.initialize(
            KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return g.generateKeyPair()
    }

    fun write(name: String, plain: ByteArray): Boolean = runCatching {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = c.iv
        val ct = c.doFinal(plain)
        File(context.filesDir, name).writeBytes(byteArrayOf(iv.size.toByte()) + iv + ct)
    }.onFailure { Log.w(TAG, "암호화 저장 실패", it) }.isSuccess

    fun read(name: String): ByteArray? = runCatching {
        val f = File(context.filesDir, name)
        if (!f.exists()) return null
        val b = f.readBytes()
        val n = b[0].toInt()
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, b, 1, n))
        c.doFinal(b, 1 + n, b.size - 1 - n)
    }.onFailure { Log.w(TAG, "복호화 실패", it) }.getOrNull()

    fun size(name: String): Long = File(context.filesDir, name).let { if (it.exists()) it.length() else 0L }

    fun delete(name: String) { File(context.filesDir, name).delete() }

    /** 서버로 보낼 묶음에 차량 개인키로 서명 (Base64) */
    fun sign(data: ByteArray): String = runCatching {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(signKeys().private)
        s.update(data)
        Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "서명 실패", it) }.getOrDefault("unsigned")

    /** 공개키 지문 앞 8자리 — 서버는 이 값으로 어느 차의 업데이트인지(누구인지는 모름) 검증한다 */
    val keyId: String by lazy {
        runCatching {
            MessageDigest.getInstance("SHA-256").digest(signKeys().public.encoded).take(4).joinToString("") { "%02x".format(it) }
        }.getOrDefault("--------")
    }

    companion object {
        private const val TAG = "SecureVault"
        private const val PROVIDER = "AndroidKeyStore"
        private const val AES_ALIAS = "yeopjari_personal_vault"
        private const val SIGN_ALIAS = "yeopjari_device_sign"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
