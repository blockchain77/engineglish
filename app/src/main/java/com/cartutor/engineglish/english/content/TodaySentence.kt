package com.cartutor.engineglish.english.content

import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.Track
import kotlin.math.abs

/**
 * '오늘의 한 문장' 고르기 — 도착 3분 전 마무리 (명세 §7 Wrap-up).
 * 코치는 세션에서 가장 점수가 낮았던 문장을 먼저 쓰고, 없을 때 이 풀에서 고른다.
 * 어떤 ContentSource 든 TODAY_TAG 태그로 찾으므로 테스트용 가짜 콘텐츠에서도 동작한다.
 */
object TodaySentence {

    /** 트랙·레벨에 맞는 후보. 없으면 같은 트랙의 가까운 레벨, 그래도 없으면 전체 */
    fun pool(content: ContentSource, track: Track, level: Level): List<LearningItem> {
        val all = (content as? JsonContentSource)?.todaySentences()
            ?: content.items().filter { ContentExtras.TODAY_TAG in it.tags }
        val exact = all.filter { it.track == track && it.level == level }
        if (exact.isNotEmpty()) return exact
        val sameTrack = all.filter { it.track == track }.sortedBy { abs(it.level.ordinal - level.ordinal) }
        return sameTrack.ifEmpty { all }
    }

    /**
     * 날짜마다 바뀌는 결정적 선택 (같은 날엔 같은 문장).
     * @param epochDay KST epochDay (Kst.epochDay)
     * @param exclude 이번 세션에 이미 낸 문항 id
     */
    fun pick(content: ContentSource, track: Track, level: Level, epochDay: Long, exclude: Set<String> = emptySet()): LearningItem? {
        val pool = pool(content, track, level)
        val candidates = pool.filter { it.id !in exclude }.ifEmpty { pool }
        if (candidates.isEmpty()) return null
        val index = Math.floorMod(epochDay, candidates.size.toLong()).toInt()
        return candidates[index]
    }
}
