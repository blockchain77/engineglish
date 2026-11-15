package com.cartutor.engineglish.english.content

import android.content.Context
import java.io.IOException

/**
 * 앱 진입점 — assets 의 content 폴더를 AssetManager 로 읽는다 (네트워크 없음).
 * 파싱은 첫 접근 때 한 번만 한다. 시작을 빠르게 하려면 백그라운드에서 [warmUp] 을 불러 둔다.
 */
class ContentModule(context: Context) {

    private val assets = context.applicationContext.assets

    val source: JsonContentSource = JsonContentSource { path ->
        try {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            null
        }
    }

    /** 콘텐츠를 미리 파싱한다 (아무 스레드에서나 불러도 안전). 문제 개수를 돌려준다 */
    fun warmUp(): Int {
        source.items()
        return source.problems.size
    }
}
