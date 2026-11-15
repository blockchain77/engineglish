package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.SecureStore
import com.cartutor.engineglish.platform.secure.SecureVault

/**
 * SecureVault(AES-256-GCM, Android Keystore) → SecureStore 어댑터.
 * Keystore 를 쓸 수 없는 기기에서는 쓰기가 실패하고(평문 대체 저장 없음), 학습 기록은 메모리에만 남는다.
 */
class VaultSecureStore(private val vault: SecureVault) : SecureStore {
    override fun write(name: String, bytes: ByteArray): Boolean = vault.write(name, bytes)
    override fun read(name: String): ByteArray? = vault.read(name)
    override fun delete(name: String) = vault.delete(name)
    override fun size(name: String): Long = vault.size(name)
    override val encrypted: Boolean get() = vault.available
}
