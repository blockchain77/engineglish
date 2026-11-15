package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.LearnerRepository
import com.yeopjari.app.english.core.PlacementEngine
import com.yeopjari.app.english.core.Progression
import com.yeopjari.app.english.core.Scheduler
import com.yeopjari.app.english.core.SecureStore
import com.yeopjari.app.english.core.SessionPlanner
import com.yeopjari.app.platform.secure.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 학습 모듈 묶음 — 통합(AppContainer)이 `LearnModule(vault, scope, clock)` 하나로 만든다.
 * 학습자 상태는 이 차 안의 암호화 파일 하나에만 저장되고 서버로 나가지 않는다.
 */
class LearnModule(vault: SecureVault, scope: CoroutineScope, clock: Clock) {
    val store: SecureStore = VaultSecureStore(vault)
    val scheduler: Scheduler = SrsScheduler()
    val placement: PlacementEngine = AdaptivePlacement()
    val progression: Progression = UnitProgression(scheduler)
    val planner: SessionPlanner = CommuteSessionPlanner(scheduler, progression)
    /** 파일 읽기·쓰기·암호화는 IO 스레드에서 (메인 스레드 멈춤 없음) */
    val repository: LearnerRepository = VaultLearnerRepository(store, scope, clock, Dispatchers.IO)

    /**
     * '지도 정보 초기화' 보조: 이전 버전이 남긴 개인 금고 파일(옛 페르소나 장소·방문 기록)을 지운다.
     * 자동으로 부르지 않는다 — 통합 단계에서 옛 기능을 지운 뒤 한 번 부른다. 지운 파일 수를 돌려준다.
     */
    fun purgeLegacyFiles(): Int {
        var removed = 0
        for (name in LEGACY_FILES) {
            if (store.size(name) > 0L) {
                store.delete(name)
                removed++
            }
        }
        return removed
    }

    companion object {
        /** 옛 PersonalService 금고 파일 (페르소나 장소·방문 기록) */
        val LEGACY_FILES: List<String> = listOf("personal.vault")
    }
}
