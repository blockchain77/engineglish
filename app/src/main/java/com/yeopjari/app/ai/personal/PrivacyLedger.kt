package com.yeopjari.app.ai.personal

import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 학습 기록 사슬 — 블록체인의 '해시 체인' 개념만 빌려 온 차 안 감사 기록.
 *
 * 각 기록은 직전 기록의 해시를 품고 있어서, 중간 기록 하나를 몰래 고치거나 지우면
 * 뒤의 해시가 모두 어긋나 [verify]가 실패한다. 분산 원장(네트워크)은 쓰지 않는다 —
 * 개인 데이터는 지울 수 있어야 하므로 지울 수 없는 공개 원장에 올리지 않는다.
 */
data class LedgerEntry(
    val seq: Int,
    val time: Long,
    val type: String,
    val summary: String,
    val prev: String,
    val hash: String,
)

object HashChain {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun digest(prev: String, seq: Int, time: Long, type: String, summary: String) = sha256Hex("$prev|$seq|$time|$type|$summary")

    fun append(chain: List<LedgerEntry>, type: String, summary: String, time: Long): LedgerEntry {
        val prev = chain.lastOrNull()?.hash ?: GENESIS
        val seq = (chain.lastOrNull()?.seq ?: 0) + 1
        return LedgerEntry(seq, time, type, summary, prev, digest(prev, seq, time, type, summary))
    }

    fun verify(chain: List<LedgerEntry>): Boolean {
        var prev = GENESIS
        for (e in chain) {
            if (e.prev != prev) return false
            if (digest(e.prev, e.seq, e.time, e.type, e.summary) != e.hash) return false
            prev = e.hash
        }
        return true
    }

    /** [e]가 [last] 바로 뒤에 올바르게 이어지는지 — 기록을 하나 더할 때마다 사슬 전체를 다시 검사하지 않도록 */
    fun links(last: LedgerEntry?, e: LedgerEntry): Boolean =
        e.prev == (last?.hash ?: GENESIS) && e.seq == (last?.seq ?: 0) + 1 &&
            digest(e.prev, e.seq, e.time, e.type, e.summary) == e.hash
}

/**
 * 연합학습 업데이트 묶음 — 서버로 보낼 수 있는 유일한 학습 정보.
 * 장소 이름·좌표·방문 시각·음성은 들어가지 않고, 순위 가중치의 '변화량' 숫자 6개만 담긴다.
 */
data class UpdatePacket(
    val round: Int,
    val delta: List<Double>,
    val events: Int,
    val sigma: Double,
    val clipNorm: Double,
    val body: String,
    val sha256: String,
    val signature: String,
    val keyId: String,
)

object FederatedUpdate {
    const val MODEL = "yeopjari-ranker-v1"
    const val SIGMA = 0.05
    const val CLIP = 1.0
    /** 아직 서명하지 않은 미리보기 묶음의 서명 칸 */
    const val UNSIGNED = "서명 대기"

    /**
     * 서명 전 묶음 (화면 미리보기 · 전송 직전 서명할 본문).
     * 1) 변화량 = 지금 가중치 − 마지막 동기화 가중치
     * 2) 클리핑: 변화량 크기를 1.0 이하로 (한 사람이 공용 모델을 크게 흔들지 못하게)
     * 3) 가우시안 잡음(σ=0.05) 추가 — 차등 프라이버시 방식으로 개인 역추적을 어렵게.
     *    잡음은 예측할 수 없는 난수([java.security.SecureRandom])에서 뽑아야 한다 — 씨앗을 추측할 수 있으면 잡음을 빼서 원래 변화량이 드러난다.
     * 4) 본문 해시(SHA-256). 서명은 [sign]으로 전송할 때만 한다.
     */
    fun build(weights: DoubleArray, synced: DoubleArray, round: Int, events: Int, rnd: java.util.Random, keyId: String): UpdatePacket {
        val raw = DoubleArray(weights.size) { weights[it] - synced[it] }
        val norm = sqrt(raw.sumOf { it * it })
        val scale = if (norm > CLIP) CLIP / norm else 1.0
        val delta = raw.map { ((it * scale + rnd.nextGaussian() * SIGMA) * 1000).roundToInt() / 1000.0 }
        val body = "{\"model\":\"$MODEL\",\"round\":$round,\"delta\":[${delta.joinToString(",")}],\"events\":$events,\"dp_sigma\":$SIGMA,\"clip\":$CLIP}"
        return UpdatePacket(round, delta, events, SIGMA, CLIP, body, HashChain.sha256Hex(body), UNSIGNED, keyId)
    }

    /** 본문에 차량 개인키 서명(ECDSA P-256)을 붙인다 — 위조·변조 방지. 전송할 때 한 번만 부른다. */
    fun sign(p: UpdatePacket, signer: (ByteArray) -> String): UpdatePacket = p.copy(signature = signer(p.body.toByteArray(Charsets.UTF_8)))

    /** 시험용 — 같은 seed면 같은 잡음 (앱은 SecureRandom을 쓰는 위 [build]를 쓴다) */
    fun build(weights: DoubleArray, synced: DoubleArray, round: Int, events: Int, seed: Long, sign: (ByteArray) -> String, keyId: String): UpdatePacket =
        this.sign(build(weights, synced, round, events, java.util.Random(seed), keyId), sign)
}
