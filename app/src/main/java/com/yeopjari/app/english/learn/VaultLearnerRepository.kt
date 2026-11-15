package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.LearnerRepository
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.SecureStore
import com.yeopjari.app.english.core.StorageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * 학습자 상태 저장소 (명세 §6.5) — 파일 하나(english_learner_v1.bin) = LearnerState 전체 JSON 을 SecureStore 로 암호화 저장.
 * 서버로 보내지 않고, 내용은 로그에 남기지 않는다.
 *
 *  - update: 부른 스레드에서 바로 적용(원자적) → 1초 안에 저장 (첫 변경 뒤 1초에 그때의 최신 상태를 씀)
 *  - flush: 대기 중인 저장을 지금 · eraseAll: 파일 삭제 + 빈 상태
 *  - load 전에는 절대 쓰지 않는다 (저장된 기록을 빈 상태로 덮어쓰지 않게). load 전 update 는 읽은 상태 위에 다시 적용한다.
 *  - [io]: 파일 읽기/쓰기·암호화를 돌릴 곳. 앱은 Dispatchers.IO, 테스트는 기본값(부른 코루틴에서 바로)
 */
class VaultLearnerRepository(
    private val store: SecureStore,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val io: CoroutineContext = EmptyCoroutineContext,
) : LearnerRepository {

    private val _state = MutableStateFlow(LearnerState())
    override val state: StateFlow<LearnerState> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 상태·버전·예약 저장 보호 */
    private val lock = Any()
    /** 저장소 쓰기·지우기 직렬화 (오래된 상태가 새 상태를 덮지 않게) */
    private val ioLock = Any()
    private val loadMutex = Mutex()

    private var version = 0L
    private var savedVersion = 0L
    @Volatile private var generation = 0L
    private var pendingJob: Job? = null
    private var saveScheduled = false
    private val beforeLoad = ArrayList<(LearnerState) -> LearnerState>()
    @Volatile private var lastSavedMs: Long? = null

    override suspend fun load() {
        if (_loaded.value) return
        loadMutex.withLock {
            if (_loaded.value) return
            val gen = generation
            val bytes = withContext(io) { runCatching { store.read(FILE_NAME) }.getOrNull() }
            val decoded = LearnerStateCodec.decode(bytes)
            var replayed = false
            synchronized(lock) {
                // 읽는 사이 eraseAll 이 있었으면 지운 상태를 유지
                var s = if (gen == generation) decoded else LearnerState()
                for (t in beforeLoad) s = t(s)
                replayed = beforeLoad.isNotEmpty()
                beforeLoad.clear()
                _state.value = s
                if (!replayed) synchronized(ioLock) { savedVersion = maxOf(savedVersion, version) }
                _loaded.value = true
            }
            if (replayed) scheduleSave()
        }
    }

    override fun update(transform: (LearnerState) -> LearnerState): LearnerState {
        val next: LearnerState
        val save: Boolean
        synchronized(lock) {
            val cur = _state.value
            next = transform(cur)
            if (!_loaded.value) beforeLoad += transform
            if (next === cur) return cur
            _state.value = next
            version++
            save = _loaded.value
        }
        if (save) scheduleSave()
        return next
    }

    override suspend fun flush() {
        if (!_loaded.value) return
        val job: Job?
        synchronized(lock) {
            job = if (saveScheduled) pendingJob else null
            if (job != null) { pendingJob = null; saveScheduled = false }
        }
        job?.cancel()
        saveNow()
    }

    override fun eraseAll() {
        val job: Job?
        val erasedVersion: Long
        synchronized(lock) {
            generation++
            job = pendingJob
            pendingJob = null
            saveScheduled = false
            beforeLoad.clear()
            _state.value = LearnerState()
            version++
            erasedVersion = version
        }
        job?.cancel()
        synchronized(ioLock) {
            runCatching { store.delete(FILE_NAME) }
            savedVersion = maxOf(savedVersion, erasedVersion)
            lastSavedMs = null
        }
    }

    override fun storageInfo(): StorageInfo {
        val bytes = runCatching { store.size(FILE_NAME) }.getOrDefault(0L)
        return StorageInfo(
            bytes = bytes,
            files = if (bytes > 0) listOf(FILE_NAME) else emptyList(),
            encrypted = store.encrypted,
            lastSavedMs = lastSavedMs,
        )
    }

    // ───────────── 저장 ─────────────

    private fun scheduleSave() {
        synchronized(lock) {
            if (saveScheduled) return
            saveScheduled = true
        }
        val job = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            synchronized(lock) {
                saveScheduled = false
                pendingJob = null
            }
            try {
                saveNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 저장 실패로 앱이 죽지 않게 — 다음 변경이나 flush 때 다시 시도한다
            }
        }
        synchronized(lock) {
            // launch 가 바로 실행돼 이미 끝났을 수도 있다 (테스트 디스패처)
            if (saveScheduled && job.isActive) pendingJob = job
        }
    }

    private suspend fun saveNow() {
        val snapshot: LearnerState
        val v: Long
        val gen: Long
        synchronized(lock) {
            if (!_loaded.value || version <= savedVersionSafe()) return
            snapshot = _state.value
            v = version
            gen = generation
        }
        withContext(io) {
            val bytes = runCatching { LearnerStateCodec.encode(snapshot) }.getOrNull() ?: return@withContext
            synchronized(ioLock) {
                if (gen != generation || v <= savedVersion) return@withContext
                val ok = runCatching { store.write(FILE_NAME, bytes) }.getOrDefault(false)
                if (ok) {
                    savedVersion = v
                    lastSavedMs = clock.nowMs()
                }
            }
        }
    }

    private fun savedVersionSafe(): Long = synchronized(ioLock) { savedVersion }

    companion object {
        const val FILE_NAME = "english_learner_v1.bin"
        const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
