package com.cartutor.engineglish.english.content

import com.cartutor.engineglish.english.core.ContentFiles
import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.MiniJson
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** assets 의 content 폴더 JSON 전체를 파일 시스템에서 직접 읽어 스키마·분량·운전 부하 규칙을 검사한다 */
class ContentBankTest {

    /** Gradle 은 app 폴더에서 테스트를 돌린다. 저장소 루트에서 돌려도 찾게 둘 다 본다 */
    private val assetsDir: File = listOf(File("src/main/assets"), File("app/src/main/assets"))
        .firstOrNull { File(it, "content").isDirectory } ?: File("src/main/assets")

    private fun readAsset(path: String): String? = File(assetsDir, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    private val source: JsonContentSource by lazy { JsonContentSource { readAsset(it) } }

    @Test fun everyContentFileExistsAndLoadsWithoutProblems() {
        for (path in ContentExtras.ALL) assertNotNull("missing $path", readAsset(path))
        assertTrue(source.problems.joinToString("\n"), source.problems.isEmpty())
        assertTrue(source.version.startsWith("s1."))
        assertFalse(source.version.contains(".x"))
    }

    @Test fun assetsFolderHoldsOnlyKnownFiles() {
        val onDisk = File(assetsDir, "content").listFiles { f -> f.extension == "json" }!!.map { "content/${it.name}" }.toSet()
        assertEquals(ContentExtras.ALL.toSet(), onDisk)
    }

    @Test fun auditFindsNoIssues() {
        val issues = ContentAudit.run(source)
        assertTrue("${issues.size} issues:\n" + issues.take(40).joinToString("\n"), issues.isEmpty())
    }

    @Test fun idsAreUniqueAcrossAllFiles() {
        val ids = ArrayList<String>()
        fun collect(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    (node["id"] as? String)?.let { ids += it }
                    node.values.forEach { collect(it) }
                }
                is List<*> -> node.forEach { collect(it) }
            }
        }
        for (path in ContentExtras.ALL) collect(MiniJson.parse(readAsset(path)!!))
        val dup = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate ids $dup", dup.isEmpty())
        assertEquals(source.items().size + source.placementPool().size, ids.size)
    }

    @Test fun volumeMeetsTargets() {
        var trackItems = 0
        for (track in Track.entries) for (level in Level.entries) {
            val n = source.units(track, level).sumOf { source.unitItems(it.key).size }
            assertTrue("$track/$level has $n items", n >= 24)
            trackItems += n
        }
        assertTrue(trackItems >= 288)
        assertTrue(source.placementPool().size >= 72)
        for (t in HookTrigger.entries) assertTrue("$t", source.hook(t)!!.items.size >= 3)
        for (type in PronIssueType.entries) {
            assertTrue("$type", source.pronDrills(type).count { it.id.startsWith("pron.") } >= 5)
        }
        assertTrue(source.todaySentences().size >= 24)
        val counts = ContentAudit.counts(source)
        println("content ${source.version} · track items $trackItems")
        counts.forEach { (k, v) -> println("  $k = $v") }
    }

    @Test fun drivingItemsRespectPolicyWordAndPromptLimits() {
        for (item in source.items()) {
            val w = item.answerWords
            when (item.maxLoad) {
                LoadLevel.NORMAL -> assertTrue("${item.id} $w words", w <= 12)
                LoadLevel.RELAXED -> assertTrue("${item.id} $w words", w <= 20)
                LoadLevel.PARKED -> Unit
                else -> throw AssertionError("${item.id} maxLoad ${item.maxLoad}")
            }
            if (item.maxLoad != LoadLevel.PARKED) assertTrue("${item.id} promptKo", item.promptKo.length <= 40)
            if (item.skill == Skill.TOEIC_QA) assertEquals(item.id, LoadLevel.RELAXED, item.maxLoad)
            if (item.skill != Skill.TOEIC_QA) assertTrue(item.id, item.listenSeconds <= 16)
        }
        // 운전 중(NORMAL)에 낼 수 있는 문항이 트랙·레벨마다 넉넉해야 한다
        for (track in Track.entries) for (level in Level.entries) {
            val items = source.units(track, level).flatMap { source.unitItems(it.key) }
            val normal = items.count { it.maxLoad == LoadLevel.NORMAL }
            assertTrue("$track/$level NORMAL share", normal * 10 >= items.size * 6)
            if (track == Track.TOEIC_SPEAKING) assertTrue(items.any { it.skill == Skill.TOEIC_QA && it.maxLoad == LoadLevel.RELAXED })
        }
    }

    @Test fun interfaceQueriesAreConsistent() {
        for (track in Track.entries) for (level in Level.entries) {
            val units = source.units(track, level)
            assertEquals((1..units.size).toList(), units.map { it.key.unit })
            for (u in units) {
                assertEquals(u, source.unit(u.key))
                assertEquals(u.itemIds, source.unitItems(u.key).map { it.id })
            }
            val quiz = source.quizItems(track, level)
            assertTrue(quiz.size >= 5)
            assertTrue(quiz.all { it.distractors.size >= 2 && it.track == track && it.level == level })
            val pool = source.placement(track)
            assertTrue(pool.all { it.track == null || it.track == track })
            assertTrue(pool.any { it.track == null } && pool.any { it.track == track })
        }
        for (item in source.items()) assertEquals(item, source.item(item.id))
        assertNull(source.item("no.such.item"))
        assertTrue(source.unitItems(UnitKey(Track.DAILY, Level.BEGINNER, 99)).isEmpty())
        for (type in PronIssueType.entries) {
            val drills = source.pronDrills(type)
            assertTrue("$type", drills.size >= 6)
            assertTrue(drills.all { it.skill == Skill.MINIMAL_PAIR && it.pronFocus == type && it.contrast != null })
        }
        for (t in HookTrigger.entries) assertTrue(source.hook(t)!!.items.all { it.contextHook == t && it.unit == 0 })
        // 결정적 순서: 다시 읽어도 같은 목록
        val again = JsonContentSource { readAsset(it) }
        assertEquals(source.items().map { it.id }, again.items().map { it.id })
        assertEquals(source.version, again.version)
        // 파일 순서: 트랙 파일(유닛 순) → 훅 → 발음 → 오늘의 한 문장
        val firstIds = source.items().take(3).map { it.id }
        assertEquals(listOf("biz.b1.01", "biz.b1.02", "biz.b1.03"), firstIds)
    }

    @Test fun placementItemsAreAnswerable() {
        for (p in source.placementPool()) {
            assertTrue(p.id, p.answerIndex in p.choices.indices)
            assertEquals(p.id, p.choices.size, p.choices.toSet().size)
            if (p.skill == Skill.LISTEN_REPEAT) assertNotNull(p.id, p.audioEn)
        }
        for (track in Track.entries) for (level in Level.entries) {
            assertTrue("$track/$level", source.placement(track).count { it.level == level } >= 12)
        }
    }

    @Test fun loaderSkipsBrokenItemsAndNeverCrashes() {
        val item = { id: String, extra: String ->
            """{ "id": "$id", "skill": "VOCAB", "promptKo": "‘미루다’를 영어로?", "answers": ["postpone", "delay"],
                 "keywords": ["postpone"], "hintWord": "postpone", "meaningKo": "미루다", "distractors": ["prepare", "propose"] $extra }"""
        }
        val business = """
            { "schema": 1, "track": "BUSINESS", "units": [
              { "level": "BEGINNER", "unit": 1, "titleKo": "테스트", "titleEn": "Test", "items": [
                ${item("t.ok.01", "")},
                ${item("t.ok.01", "")},
                ${item("t.bad.02", ", \"skill\": \"DANCE\"")},
                ${item("t.bad.03", ", \"maxLoad\": \"FOCUS\"")},
                ${item("t.bad.04", ", \"skill\": \"MINIMAL_PAIR\", \"contrast\": \"x\"")},
                ${item("T.BAD", "")},
                { "id": "t.bad.05", "skill": "VOCAB", "promptKo": "x", "meaningKo": "x", "answers": [] },
                ${item("t.ok.06", ", \"maxLoad\": \"RELAXED\", \"unknownField\": 3")},
                7
              ] },
              { "level": "NOPE", "unit": 2, "items": [] }
            ] }
        """.trimIndent()
        val placement = """{ "schema": 1, "items": [
            { "id": "p.ok.01", "track": null, "level": "BEGINNER", "skill": "VOCAB", "questionKo": "고르세요", "choices": ["a", "b", "c"], "answerIndex": 2 },
            { "id": "p.bad.02", "track": "MARS", "level": "BEGINNER", "skill": "VOCAB", "questionKo": "고르세요", "choices": ["a", "b"], "answerIndex": 0 },
            { "id": "p.bad.03", "track": null, "level": "BEGINNER", "skill": "VOCAB", "questionKo": "고르세요", "choices": ["a", "b"], "answerIndex": 5 }
        ] }"""
        val files = mapOf(
            ContentFiles.TRACKS.getValue(Track.BUSINESS) to business,
            ContentFiles.PLACEMENT to placement,
            ContentFiles.HOOKS to "{ \"schema\": 1, \"hooks\": [ { broken",
        )
        val s = JsonContentSource { files[it] }
        assertEquals(listOf("t.ok.01", "t.ok.06"), s.items().map { it.id })
        assertEquals(LoadLevel.RELAXED, s.item("t.ok.06")!!.maxLoad)
        assertEquals(listOf("t.ok.01", "t.ok.06"), s.unitItems(UnitKey(Track.BUSINESS, Level.BEGINNER, 1)).map { it.id })
        assertEquals(listOf("p.ok.01"), s.placement(Track.DAILY).map { it.id })
        assertNull(s.hook(HookTrigger.RAIN))
        assertTrue(s.units(Track.DAILY, Level.BEGINNER).isEmpty())
        val p = s.problems.joinToString("\n")
        for (needle in listOf("duplicate id", "bad track/level/skill", "maxLoad", "MINIMAL_PAIR needs", "bad id", "answers are required",
                "is not an object", "units[1]", "invalid JSON", "missing file", "bad choices/answerIndex")) {
            assertTrue("expected '$needle' in\n$p", p.contains(needle))
        }
        assertTrue(s.version.contains(".x"))
        // 아무것도 없을 때도 안전
        val empty = JsonContentSource { null }
        assertTrue(empty.items().isEmpty())
        assertEquals(ContentExtras.ALL.size, empty.problems.size)
    }

    @Test fun todaySentenceIsStablePerDayAndFallsBack() {
        val a = TodaySentence.pick(source, Track.BUSINESS, Level.BEGINNER, 20_000L)!!
        assertEquals(a, TodaySentence.pick(source, Track.BUSINESS, Level.BEGINNER, 20_000L))
        assertEquals(Track.BUSINESS, a.track)
        assertEquals(Skill.LISTEN_REPEAT, a.skill)
        val week = (0L until 7L).mapNotNull { TodaySentence.pick(source, Track.DAILY, Level.ADVANCED, 20_000L + it)?.id }.toSet()
        assertTrue(week.size >= 2)
        val other = TodaySentence.pick(source, Track.BUSINESS, Level.BEGINNER, 20_000L, exclude = setOf(a.id))!!
        assertTrue(other.id != a.id)
        // 태그만 보는 다른 ContentSource 에서도 동작
        val wrapped = object : ContentSource by source {}
        assertEquals(a.id, TodaySentence.pick(wrapped, Track.BUSINESS, Level.BEGINNER, 20_000L)!!.id)
        assertNull(TodaySentence.pick(JsonContentSource { null }, Track.TRAVEL, Level.BEGINNER, 1L))
    }

    @Test fun keywordMatcherFollowsJudgeMorphology() {
        assertTrue(ContentAudit.keywordIn("meeting", "Can we push the meeting to tomorrow?"))
        assertTrue(ContentAudit.keywordIn("attach", "Please see the attached file."))
        assertTrue(ContentAudit.keywordIn("write", "I'm writing to ask about it."))
        assertTrue(ContentAudit.keywordIn("company", "Companies can help."))
        assertTrue(ContentAudit.keywordIn("drop", "Sales dropped."))
        assertTrue(ContentAudit.keywordIn("late", "I'm running late."))
        assertFalse(ContentAudit.keywordIn("quick", "Thanks for getting back so quickly."))
        assertFalse(ContentAudit.keywordIn("agree", "I disagree."))
        assertEquals("i am running late", ContentAudit.norm("I’m running late!"))
    }
}
