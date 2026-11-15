package com.yeopjari.app.ai.personal

import com.yeopjari.app.platform.secure.SecureVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 화면이 보는 개인화 엔진 상태 */
data class PersonalUi(
    val weights: List<Double>,
    val prevWeights: List<Double>,
    /** 기억한 표현: 들린 말 → 알아들은 이름 */
    val aliases: List<Pair<String, String>>,
    val learnCount: Int,
    val ledger: List<LedgerEntry>,
    val ledgerValid: Boolean,
    val visitCount: Int,
    val placeCount: Int,
    val erased: Boolean,
    val lastResolution: Resolution?,
    val lastLearn: LearnOutcome?,
    /**
     * 서버로 보낼 수 있는 유일한 학습 정보 — 미리보기 (서명 전: signature = [FederatedUpdate.UNSIGNED]).
     * 가중치가 바뀔 때만 다시 만들고, 서명은 전송([PersonalService.markSynced])할 때만 한다.
     */
    val packet: UpdatePacket,
    val encrypted: Boolean,
    val keyId: String,
    val vaultBytes: Long,
    /** 차량 내비(NaviHelper)의 최근 목적지·즐겨찾기에서 들어온 장소 수 */
    val vehiclePlaces: Int = 0,
    /** 실제 도착(onDestinationArrived)으로 쌓인 방문 기록 수 */
    val realVisits: Int = 0,
    /** 마지막으로 서명해 보낸 묶음 (모의 전송) */
    val sentPacket: UpdatePacket? = null,
)

/**
 * 개인화 엔진 서비스 — [PersonalBrain]을 차 안 암호화 금고([SecureVault])와 묶는다.
 * 방문 기록·기억한 표현·가중치·학습 기록 사슬은 모두 이 기기 안에서만 읽고 쓴다.
 */
class PersonalService(private val vault: SecureVault?) {
    val brain = PersonalBrain()
    private val ledger = mutableListOf<LedgerEntry>()
    /** 학습 기록 사슬이 온전한지 — 불러올 때 한 번 전체를 검사하고, 그 뒤로는 기록을 더할 때 새 기록만 확인한다 */
    private var ledgerOk = true
    /** 실차 내비에서 들어온 장소 (최근 목적지·즐겨찾기·도착지) */
    private val vehiclePois = mutableListOf<Poi>()
    /** 실제 도착 이벤트로 쌓인 방문 기록 */
    private val realVisits = mutableListOf<Visit>()
    private var synced = PersonalBrain.DEFAULT_WEIGHTS.copyOf()
    private var round = 1
    private var erased = false
    private var lastRes: Resolution? = null
    private var lastLearn: LearnOutcome? = null

    /** 차등 프라이버시 잡음용 난수 — 예측할 수 없어야 잡음을 빼서 원래 변화량을 되살릴 수 없다 */
    private val noise = SecureRandom()
    /** 미리보기 묶음 — 가중치·동기화 상태가 바뀔 때만 다시 만든다 (화면에 보인 것 = 보낼 때 서명할 본문) */
    private var preview: UpdatePacket? = null
    private var previewKey = ""
    private var sent: UpdatePacket? = null

    /** 암호화 저장(AES-GCM + 파일 쓰기)은 화면 스레드 밖에서 순서대로 */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "personal-vault").apply { isDaemon = true } }
    @Volatile private var vaultBytes = 0L

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<PersonalUi> = _ui.asStateFlow()

    init {
        load()
        ledgerOk = HashChain.verify(ledger)
        vaultBytes = vault?.size(FILE) ?: 0L
        applyData()
        if (ledger.isEmpty()) append("저장소 생성", "가상 운전자 기록 ${brain.visits.size}건 · AES-256-GCM 암호화 저장")
        save()
        emit()
    }

    /** 장소 사전 = 가상 운전자 장소 + 실차 내비 장소 / 방문 기록 = 가상 기록(삭제 전) + 실제 도착 기록 */
    private fun applyData() {
        val names = PersonaFactory.pois.map { QueryParser.norm(it.place.name) }.toSet()
        brain.replacePois(PersonaFactory.pois + vehiclePois.filter { QueryParser.norm(it.place.name) !in names })
        brain.replaceVisits((if (erased) emptyList() else PersonaFactory.visits()) + realVisits)
    }

    /**
     * 차량 내비(NaviHelper)의 최근 목적지·즐겨찾기를 장소 사전에 합친다.
     * 이름이 이미 있으면 건너뛰고, 즐겨찾기 집·회사는 별칭으로 연결한다.
     */
    fun syncVehiclePlaces(recent: List<com.yeopjari.app.core.model.Place>, bookmarks: List<com.yeopjari.app.core.model.Place>) {
        val before = vehiclePois.size
        for (p in bookmarks + recent) {
            if (p.lat == 0.0 && p.lng == 0.0) continue
            if (vehiclePois.any { it.place.id == p.id || QueryParser.norm(it.place.name) == QueryParser.norm(p.name) }) continue
            vehiclePois += Poi(p, brandOf(p.name), categoryOf(p.name), when (p.kind) {
                com.yeopjari.app.core.model.PlaceKind.HOME -> listOf("집", "우리 집")
                com.yeopjari.app.core.model.PlaceKind.WORK -> listOf("회사")
                else -> emptyList()
            })
        }
        android.util.Log.i(TAG, "내비 동기화: 최근 목적지 ${recent.size} · 즐겨찾기 ${bookmarks.size} → 사전 추가 ${vehiclePois.size - before}")
        if (vehiclePois.size != before) {
            append("실차 장소 동기화", "내비 최근 목적지·즐겨찾기 ${vehiclePois.size - before}곳 추가 (차 안 저장)")
            applyData(); save(); emit()
        }
    }

    /** 실제 목적지 도착 → 방문 기록 1건 (장소가 사전에 없으면 새로 추가) */
    fun recordArrival(place: com.yeopjari.app.core.model.Place, s: Situation) {
        val match = brain.pois.firstOrNull { it.place.id == place.id }
            ?: brain.pois.firstOrNull { it.names.any { n -> Hangul.similarity(place.name, n) >= 0.97 } }
        val poi = match ?: Poi(place, brandOf(place.name), categoryOf(place.name)).also { vehiclePois += it }
        realVisits += Visit(poi.place.id, s.epochDay, s.dow, s.minute)
        android.util.Log.i(TAG, "도착 기록: ${poi.spoken} (실차 방문 ${realVisits.size}건)")
        append("도착 기록", "${poi.spoken} · 실차 방문 ${realVisits.size}건")
        applyData(); save(); emit()
    }

    private fun brandOf(name: String): String? =
        listOf("스타벅스", "성심당", "현대백화점", "파리바게뜨", "롯데마트", "홈플러스", "이마트", "투썸플레이스", "이디야", "맥도날드", "올리브영", "다이소", "코스트코")
            .firstOrNull { name.startsWith(it) }

    private fun categoryOf(name: String): Category = when {
        listOf("빵", "베이커리", "성심당", "파리바게뜨", "뚜레쥬르").any { it in name } -> Category.BAKERY
        listOf("스타벅스", "카페", "커피", "투썸", "이디야").any { it in name } -> Category.CAFE
        listOf("마트", "홈플러스", "코스트코").any { it in name } -> Category.MART
        listOf("백화점", "아울렛").any { it in name } -> Category.DEPT
        listOf("병원", "의원", "소아과", "치과").any { it in name } -> Category.HOSPITAL
        listOf("유치원", "어린이집").any { it in name } -> Category.KIDS
        "공원" in name -> Category.PARK
        else -> Category.OTHER
    }

    fun resolve(q: PlaceQuery, s: Situation): Resolution {
        val r = brain.resolve(q, s)
        lastRes = r
        emit()
        return r
    }

    fun learn(res: Resolution, chosenId: String, lr: Double = 0.6): LearnOutcome? {
        val o = brain.learn(res, chosenId, lr) ?: return null
        lastLearn = o
        append("선택 학습", "‘${res.query.text.take(14)}’ → ${o.chosen.poi.spoken} · 가중치 6개 갱신")
        o.alias?.let { append("표현 기억", "‘${it.first}’ = ${it.second}") }
        save()
        emit()
        return o
    }

    /**
     * 운전자가 "아니야"로 거절한 후보를 이 말의 선택 기억에서 덜어 낸다.
     * (거절도 학습 신호 — 같은 말에 같은 곳을 다시 1순위로 밀지 않게)
     */
    fun forget(res: Resolution, rejectedId: String) {
        if (brain.forgetChoice(res.query.text, rejectedId, res.situation?.dow)) {
            append("거절 학습", "‘${res.query.text.take(14)}’ ≠ ${spokenOf(rejectedId)}")
            save(); emit()
        }
    }

    private fun spokenOf(id: String): String = brain.pois.firstOrNull { it.place.id == id }?.spoken ?: id

    fun toggleFavorite(placeId: String) {
        if (!brain.favorites.add(placeId)) brain.favorites.remove(placeId)
        save(); emit()
    }

    /** 학습한 것만 초기화 (방문 기록 유지) — 시연을 처음부터 다시 할 때 */
    fun resetLearning() {
        brain.resetLearning()
        synced = PersonalBrain.DEFAULT_WEIGHTS.copyOf()
        lastLearn = null
        lastRes = null
        append("학습 초기화", "가중치·기억한 표현 초기화 (방문 기록 유지)")
        save(); emit()
    }

    /** 사용자 요청으로 개인 기록 전체 삭제 — 수첩(DB) 방식이라 즉시 지워진다 */
    fun erase() {
        erased = true
        realVisits.clear()
        vehiclePois.clear()
        brain.favorites.clear()
        applyData()
        brain.resetLearning()
        synced = PersonalBrain.DEFAULT_WEIGHTS.copyOf()
        lastRes = null
        lastLearn = null
        append("기록 삭제", "방문 기록·실차 장소·기억한 표현·가중치 삭제 (사용자 요청)")
        save(); emit()
    }

    fun restore() {
        erased = false
        applyData()
        append("가상 기록 복원", "가상 운전자 기록 ${brain.visits.size}건")
        save(); emit()
    }

    /**
     * 연합학습 업데이트 전송(모의) — 실제 서비스에서는 주차·충전 중 Wi-Fi에서 보낸다.
     * 화면에 보이던 미리보기 본문 그대로에 이때 한 번만 차량 개인키로 서명한다.
     */
    fun markSynced() {
        val keyId = vault?.keyId ?: "--------"
        val p = FederatedUpdate.sign(previewPacket(keyId)) { vault?.sign(it) ?: "unsigned" }
        sent = p
        synced = brain.weights.copyOf()
        round++
        append("업데이트 전송(모의)", "변화량 6개 · SHA-256 ${p.sha256.take(8)} · 서명 키 ${p.keyId}")
        save(); emit()
    }

    private fun append(type: String, summary: String) {
        val e = HashChain.append(ledger, type, summary, System.currentTimeMillis())
        ledgerOk = ledgerOk && HashChain.links(ledger.lastOrNull(), e)
        ledger += e
    }

    private fun emit() {
        val snap = snapshot()
        _ui.value = snap
        // 그 사이 저장 스레드가 파일 크기를 바꿨으면 덮어쓰지 않게 다시 반영
        val bytes = vaultBytes
        if (snap.vaultBytes != bytes) _ui.update { it.copy(vaultBytes = bytes) }
    }

    /** 서명 전 미리보기 — 가중치·동기화 상태가 그대로면 같은 묶음(같은 잡음)을 다시 쓴다 */
    private fun previewPacket(keyId: String): UpdatePacket {
        val key = buildString {
            brain.weights.forEach { append(it).append(',') }
            append('|')
            synced.forEach { append(it).append(',') }
            append('|').append(round).append('|').append(brain.learnCount).append('|').append(keyId)
        }
        preview?.let { if (key == previewKey) return it }
        return FederatedUpdate.build(brain.weights, synced, round, brain.learnCount, noise, keyId).also {
            preview = it
            previewKey = key
        }
    }

    private fun snapshot(): PersonalUi {
        val keyId = vault?.keyId ?: "--------"
        return PersonalUi(
            weights = brain.weights.toList(),
            prevWeights = brain.prevWeights.toList(),
            aliases = brain.aliases.map { (k, v) -> k to spokenOf(v) },
            learnCount = brain.learnCount,
            ledger = ledger.toList(),
            ledgerValid = ledgerOk,
            visitCount = brain.visits.size,
            placeCount = brain.pois.size,
            erased = erased,
            lastResolution = lastRes,
            lastLearn = lastLearn,
            packet = previewPacket(keyId),
            encrypted = vault?.available == true,
            keyId = keyId,
            vaultBytes = vaultBytes,
            vehiclePlaces = vehiclePois.size,
            realVisits = realVisits.size,
            sentPacket = sent,
        )
    }

    // ───────────────────────── 암호화 저장 ─────────────────────────

    /** 지금 상태를 이 스레드에서 직렬화(일관된 사본)하고, 암호화·파일 쓰기는 저장 전용 스레드에서 순서대로 한다 */
    private fun save() {
        val v = vault ?: return
        val bytes = serialize().toByteArray(Charsets.UTF_8)
        writer.execute {
            v.write(FILE, bytes)
            val size = v.size(FILE)
            vaultBytes = size
            _ui.update { it.copy(vaultBytes = size) }
        }
    }

    private fun serialize(): String {
        val o = JSONObject()
        o.put("v", 2)
        o.put("weights", JSONArray().also { a -> brain.weights.forEach { a.put(it) } })
        o.put("prev", JSONArray().also { a -> brain.prevWeights.forEach { a.put(it) } })
        o.put("synced", JSONArray().also { a -> synced.forEach { a.put(it) } })
        o.put("round", round)
        o.put("learnCount", brain.learnCount)
        o.put("erased", erased)
        o.put("aliases", JSONObject().also { a -> brain.aliases.forEach { (k, t) -> a.put(k, t) } })
        o.put("memory", JSONObject().also { m -> brain.choiceMemory.forEach { (k, inner) -> m.put(k, JSONObject().also { j -> inner.forEach { (p, c) -> j.put(p, c) } }) } })
        // v2: 같은 선택 기억을 [평일, 주말]로 나눠서도 남긴다 (v1 파일은 'memory'만 있어 요일을 모르는 기록으로 읽힌다)
        o.put("memoryDay", JSONObject().also { m ->
            brain.choiceByDay.forEach { (k, inner) -> m.put(k, JSONObject().also { j -> inner.forEach { (p, a) -> j.put(p, JSONArray().put(a[0]).put(a[1])) } }) }
        })
        o.put("favorites", JSONArray().also { a -> brain.favorites.forEach { a.put(it) } })
        o.put("vehiclePois", JSONArray().also { a ->
            vehiclePois.forEach { p ->
                a.put(JSONObject().put("id", p.place.id).put("name", p.place.name).put("kind", p.place.kind.name).put("lat", p.place.lat).put("lng", p.place.lng)
                    .put("addr", p.place.address).put("brand", p.brand ?: "").put("cat", p.category.name).put("aliases", JSONArray().also { al -> p.aliases.forEach { al.put(it) } }))
            }
        })
        o.put("realVisits", JSONArray().also { a -> realVisits.forEach { v -> a.put(JSONObject().put("id", v.placeId).put("day", v.epochDay).put("dow", v.dow).put("min", v.minute)) } })
        o.put("ledger", JSONArray().also { a ->
            ledger.forEach { e ->
                a.put(JSONObject().put("seq", e.seq).put("time", e.time).put("type", e.type).put("summary", e.summary).put("prev", e.prev).put("hash", e.hash))
            }
        })
        return o.toString()
    }

    private fun load() {
        val bytes = vault?.read(FILE) ?: return
        runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            fun arr(name: String): DoubleArray? = o.optJSONArray(name)?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            arr("weights")?.takeIf { it.size == PersonalBrain.N_FEATURES }?.let { brain.weights = it }
            arr("prev")?.takeIf { it.size == PersonalBrain.N_FEATURES }?.let { brain.prevWeights = it }
            arr("synced")?.takeIf { it.size == PersonalBrain.N_FEATURES }?.let { synced = it }
            round = o.optInt("round", 1)
            brain.learnCount = o.optInt("learnCount", 0)
            erased = o.optBoolean("erased", false)
            o.optJSONArray("vehiclePois")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    val kind = runCatching { com.yeopjari.app.core.model.PlaceKind.valueOf(p.getString("kind")) }.getOrDefault(com.yeopjari.app.core.model.PlaceKind.DESTINATION)
                    val place = com.yeopjari.app.core.model.Place(p.getString("id"), p.getString("name"), kind, p.getDouble("lat"), p.getDouble("lng"), p.optString("addr"))
                    val al = p.optJSONArray("aliases")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
                    vehiclePois += Poi(place, p.optString("brand").ifBlank { null }, runCatching { Category.valueOf(p.getString("cat")) }.getOrDefault(Category.OTHER), al)
                }
            }
            o.optJSONArray("realVisits")?.let { a ->
                for (i in 0 until a.length()) {
                    val v = a.getJSONObject(i)
                    realVisits += Visit(v.getString("id"), v.getInt("day"), v.getInt("dow"), v.getInt("min"))
                }
            }
            o.optJSONObject("aliases")?.let { a -> a.keys().forEach { k -> brain.aliases[k] = a.getString(k) } }
            o.optJSONObject("memory")?.let { m ->
                m.keys().forEach { k ->
                    val inner = m.getJSONObject(k)
                    val map = LinkedHashMap<String, Int>()
                    inner.keys().forEach { p -> map[p] = inner.getInt(p) }
                    brain.choiceMemory[k] = map
                }
            }
            o.optJSONObject("memoryDay")?.let { m ->
                m.keys().forEach { k ->
                    val inner = m.getJSONObject(k)
                    val map = LinkedHashMap<String, IntArray>()
                    inner.keys().forEach { p -> inner.optJSONArray(p)?.let { a -> map[p] = intArrayOf(a.optInt(0), a.optInt(1)) } }
                    // 전체 횟수보다 많이 적혀 있으면 믿지 않는다 (손상된 파일)
                    if (map.isNotEmpty() && map.all { (p, a) -> a[0] + a[1] <= (brain.choiceMemory[k]?.get(p) ?: 0) }) brain.choiceByDay[k] = map
                }
            }
            o.optJSONArray("favorites")?.let { f -> for (i in 0 until f.length()) brain.favorites += f.getString(i) }
            o.optJSONArray("ledger")?.let { l ->
                for (i in 0 until l.length()) {
                    val e = l.getJSONObject(i)
                    ledger += LedgerEntry(e.getInt("seq"), e.getLong("time"), e.getString("type"), e.getString("summary"), e.getString("prev"), e.getString("hash"))
                }
            }
        }
    }

    companion object {
        const val FILE = "personal.vault"
        private const val TAG = "Personal"
    }
}
