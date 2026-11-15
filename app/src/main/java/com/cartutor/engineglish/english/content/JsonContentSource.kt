package com.cartutor.engineglish.english.content

import com.cartutor.engineglish.english.core.ContentFiles
import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.ContextHookContent
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.MiniJson
import com.cartutor.engineglish.english.core.PlacementItem
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.UnitInfo
import com.cartutor.engineglish.english.core.UnitKey
import com.cartutor.engineglish.english.core.jArr
import com.cartutor.engineglish.english.core.jInt
import com.cartutor.engineglish.english.core.jStr
import com.cartutor.engineglish.english.core.jStrList

/*
 * 엔진글리시 — 학습 콘텐츠 로더 (assets 의 content 폴더 JSON, 명세 §11).
 * 순수 Kotlin: 파일 읽기는 생성자의 read 함수에 맡긴다 (앱 = AssetManager, 테스트 = 파일 시스템).
 * 잘못된 문항은 건너뛰고 problems 에 남긴다 — 콘텐츠 때문에 앱이 멈추는 일은 없다.
 */

/** core [ContentFiles] 밖의 콘텐츠 파일 (content 담당) */
object ContentExtras {
    /** '오늘의 한 문장' 후보 풀 — 도착 3분 전 마무리 문장 */
    const val TODAY_SENTENCES = "content/today_sentences.json"
    const val TODAY_TAG = "today_sentence"
    /** 이 로더가 이해하는 schema 버전 */
    const val SCHEMA = 1

    /** 로더가 읽는 모든 파일 */
    val ALL: List<String> get() = ContentFiles.ALL + TODAY_SENTENCES
}

/**
 * [ContentSource] 구현. 첫 접근 때 한 번만 읽고 파싱한다 (스레드 안전, 이후 불변).
 * @param read assets 기준 경로("content/business.json") → 파일 내용. 없으면 null
 */
class JsonContentSource(private val read: (path: String) -> String?) : ContentSource {

    private val bank: ContentBank by lazy { ContentBankLoader(read).load() }

    /** 건너뛴 파일·문항 설명 (테스트·디버그용, 개인 정보 없음). 비어 있으면 정상 */
    val problems: List<String> get() = bank.problems

    override val version: String get() = bank.version

    override fun items(): List<LearningItem> = bank.items

    override fun item(id: String): LearningItem? = bank.byId[id]

    override fun units(track: Track, level: Level): List<UnitInfo> = bank.units[UnitGroup(track, level)].orEmpty()

    override fun unit(key: UnitKey): UnitInfo? = bank.unitByKey[key]

    override fun unitItems(key: UnitKey): List<LearningItem> =
        bank.unitByKey[key]?.itemIds?.mapNotNull { bank.byId[it] }.orEmpty()

    override fun placement(track: Track): List<PlacementItem> = bank.placementByTrack[track].orEmpty()

    override fun hook(trigger: HookTrigger): ContextHookContent? = bank.hooks[trigger]

    override fun pronDrills(type: PronIssueType): List<LearningItem> = bank.pron[type].orEmpty()

    override fun quizItems(track: Track, level: Level): List<LearningItem> = bank.quiz[UnitGroup(track, level)].orEmpty()

    /** 레벨 테스트 전체 풀 (공통 + 모든 트랙, 파일 순서) */
    fun placementPool(): List<PlacementItem> = bank.placement

    /** 상황 훅 전체 (파일 순서) */
    fun hooks(): List<ContextHookContent> = bank.hookList

    /** '오늘의 한 문장' 전체 (파일 순서) */
    fun todaySentences(): List<LearningItem> = bank.today

    /** '오늘의 한 문장' — 트랙·레벨 */
    fun todaySentences(track: Track, level: Level): List<LearningItem> =
        bank.today.filter { it.track == track && it.level == level }
}

internal data class UnitGroup(val track: Track, val level: Level)

internal class ContentBank(
    val items: List<LearningItem>,
    val byId: Map<String, LearningItem>,
    val units: Map<UnitGroup, List<UnitInfo>>,
    val unitByKey: Map<UnitKey, UnitInfo>,
    val placement: List<PlacementItem>,
    val placementByTrack: Map<Track, List<PlacementItem>>,
    val hookList: List<ContextHookContent>,
    val hooks: Map<HookTrigger, ContextHookContent>,
    val pron: Map<PronIssueType, List<LearningItem>>,
    val quiz: Map<UnitGroup, List<LearningItem>>,
    val today: List<LearningItem>,
    val problems: List<String>,
    val version: String,
)

/** 한 번 쓰고 버리는 파서 — 파일 순서 → 유닛(레벨·번호) → 유닛 안 순서로 결정적 목록을 만든다 */
internal class ContentBankLoader(private val read: (String) -> String?) {

    private val problems = ArrayList<String>()
    private val seen = HashSet<String>()
    private val items = ArrayList<LearningItem>()

    fun load(): ContentBank {
        val unitList = ArrayList<UnitInfo>()
        for ((track, path) in ContentFiles.TRACKS) readTrack(path, track, unitList)
        val trackItemCount = items.size
        val hookList = readHooks(ContentFiles.HOOKS)
        val pronItems = readItemFile(ContentFiles.PRONUNCIATION, null)
        val today = readItemFile(ContentExtras.TODAY_SENTENCES, ContentExtras.TODAY_TAG)
        val placement = readPlacement(ContentFiles.PLACEMENT)

        val byId = LinkedHashMap<String, LearningItem>(items.size * 2)
        for (item in items) byId[item.id] = item
        val trackItems = items.subList(0, trackItemCount).toList()
        val drillOrder = pronItems + trackItems.filter { it.skill == Skill.MINIMAL_PAIR }
        val quizSkills = setOf(Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD)

        val unitsByGroup = unitList.groupBy { UnitGroup(it.key.track, it.key.level) }
            .mapValues { (_, list) -> list.sortedBy { it.key.unit } }
        return ContentBank(
            items = items.toList(),
            byId = byId,
            units = unitsByGroup,
            unitByKey = unitList.associateBy { it.key },
            placement = placement,
            placementByTrack = Track.entries.associateWith { t -> placement.filter { it.track == null || it.track == t } },
            hookList = hookList,
            hooks = hookList.associateBy { it.trigger },
            pron = PronIssueType.entries.associateWith { type -> drillOrder.filter { it.pronFocus == type } },
            quiz = unitsByGroup.mapValues { (_, units) ->
                units.flatMap { u -> u.itemIds.mapNotNull { byId[it] } }
                    .filter { it.skill in quizSkills && it.distractors.size >= 2 }
            },
            today = today,
            problems = problems.toList(),
            version = "s${ContentExtras.SCHEMA}.i${items.size}.u${unitList.size}.p${placement.size}.h${hookList.size}" +
                if (problems.isEmpty()) "" else ".x${problems.size}",
        )
    }

    // ───────────── 파일 단위 ─────────────

    private fun root(path: String): Map<String, Any?>? {
        val text = try {
            read(path)
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            problem(path, "missing file")
            return null
        }
        return try {
            MiniJson.parseObject(text).also { r ->
                val schema = r.jInt("schema")
                if (schema != null && schema > ContentExtras.SCHEMA) problem(path, "newer schema $schema")
            }
        } catch (e: Exception) {
            problem(path, "invalid JSON (${e.message})")
            null
        }
    }

    private fun readTrack(path: String, expected: Track, out: MutableList<UnitInfo>) {
        val root = root(path) ?: return
        val declared = root.jStr("track")
        if (declared != expected.name) problem(path, "track '$declared' != ${expected.name}")
        val parsed = ArrayList<Pair<UnitInfo, List<LearningItem>>>()
        for ((ui, rawUnit) in root.jArr("units").orEmpty().withIndex()) {
            val u = rawUnit.asObj()
            val level = enumOf<Level>(u?.jStr("level"))
            val no = u?.jInt("unit")
            if (u == null || level == null || no == null || no < 1) {
                problem(path, "units[$ui] needs level and unit >= 1")
                continue
            }
            val key = UnitKey(expected, level, no)
            if (parsed.any { it.first.key == key }) {
                problem(path, "duplicate unit ${key.id}")
                continue
            }
            val unitItems = ArrayList<LearningItem>()
            for ((ii, rawItem) in u.jArr("items").orEmpty().withIndex()) {
                val m = rawItem.asObj()
                if (m == null) {
                    problem(path, "${key.id} items[$ii] is not an object")
                    continue
                }
                parseItem(m, path, expected, level, no, null)?.let { unitItems += it }
            }
            val info = UnitInfo(key, u.jStr("titleKo").orEmpty().trim(), u.jStr("titleEn").orEmpty().trim(), unitItems.map { it.id })
            parsed += info to unitItems
        }
        parsed.sortWith(compareBy({ it.first.key.level.ordinal }, { it.first.key.unit }))
        for ((info, list) in parsed) {
            out += info
            items += list
        }
    }

    private fun readHooks(path: String): List<ContextHookContent> {
        val root = root(path) ?: return emptyList()
        val intros = LinkedHashMap<HookTrigger, String>()
        val grouped = LinkedHashMap<HookTrigger, MutableList<LearningItem>>()
        for ((hi, raw) in root.jArr("hooks").orEmpty().withIndex()) {
            val h = raw.asObj()
            val trigger = enumOf<HookTrigger>(h?.jStr("trigger"))
            if (h == null || trigger == null) {
                problem(path, "hooks[$hi] needs a valid trigger")
                continue
            }
            val intro = h.jStr("introKo").orEmpty().trim()
            if (intro.isNotEmpty() && intros[trigger].isNullOrEmpty()) intros[trigger] = intro
            val bucket = grouped.getOrPut(trigger) { ArrayList() }
            for ((ii, rawItem) in h.jArr("items").orEmpty().withIndex()) {
                val m = rawItem.asObj()
                if (m == null) {
                    problem(path, "${trigger.name} items[$ii] is not an object")
                    continue
                }
                val item = parseItem(m, path, null, null, 0, trigger) ?: continue
                if (item.contextHook != trigger) {
                    problem("$path:${item.id}", "contextHook ${item.contextHook} != ${trigger.name}")
                    continue
                }
                bucket += item
            }
        }
        val out = grouped.map { (trigger, list) ->
            ContextHookContent(trigger, intros[trigger].orEmpty().ifEmpty { DEFAULT_HOOK_INTRO }, list.toList())
        }
        for (h in out) items += h.items
        return out
    }

    private fun readItemFile(path: String, tag: String?): List<LearningItem> {
        val root = root(path) ?: return emptyList()
        val out = ArrayList<LearningItem>()
        for ((i, raw) in root.jArr("items").orEmpty().withIndex()) {
            val m = raw.asObj()
            if (m == null) {
                problem(path, "items[$i] is not an object")
                continue
            }
            val item = parseItem(m, path, null, null, 0, null) ?: continue
            out += if (tag != null && tag !in item.tags) item.copy(tags = item.tags + tag) else item
        }
        items += out
        return out
    }

    private fun readPlacement(path: String): List<PlacementItem> {
        val root = root(path) ?: return emptyList()
        val out = ArrayList<PlacementItem>()
        for ((i, raw) in root.jArr("items").orEmpty().withIndex()) {
            val m = raw.asObj()
            if (m == null) {
                problem(path, "items[$i] is not an object")
                continue
            }
            val id = m.jStr("id")?.trim()
            val where = "$path:${id ?: "items[$i]"}"
            if (id == null || !ID_PATTERN.matches(id)) {
                problem(where, "bad id")
                continue
            }
            if (id in seen) {
                problem(where, "duplicate id")
                continue
            }
            val trackName = m.jStr("track")
            val track = enumOf<Track>(trackName)
            val level = enumOf<Level>(m.jStr("level"))
            val skill = enumOf<Skill>(m.jStr("skill"))
            val questionKo = m.jStr("questionKo").orEmpty().trim()
            val choices = m.jStrList("choices").map { it.trim() }
            val answerIndex = m.jInt("answerIndex") ?: -1
            if ((trackName != null && track == null) || level == null || skill == null || questionKo.isEmpty()) {
                problem(where, "bad track/level/skill/questionKo")
                continue
            }
            if (choices.size < 2 || choices.any { it.isEmpty() } || choices.toSet().size != choices.size || answerIndex !in choices.indices) {
                problem(where, "bad choices/answerIndex")
                continue
            }
            seen += id
            out += PlacementItem(
                id = id,
                track = track,
                level = level,
                skill = skill,
                questionKo = questionKo,
                questionEn = m.optText("questionEn"),
                choices = choices,
                answerIndex = answerIndex,
                explanationKo = m.optText("explanationKo"),
                audioEn = m.optText("audioEn"),
            )
        }
        return out
    }

    // ───────────── 문항 하나 ─────────────

    /**
     * 트랙 파일은 track/level/unit 을 감싼 파일·유닛에서 받는다 (fixedTrack/fixedLevel).
     * 그 밖의 파일(훅·발음·오늘의 한 문장)은 문항이 자기 track/level 을 갖고 unit = 0.
     */
    private fun parseItem(
        m: Map<String, Any?>,
        path: String,
        fixedTrack: Track?,
        fixedLevel: Level?,
        unit: Int,
        defaultHook: HookTrigger?,
    ): LearningItem? {
        val id = m.jStr("id")?.trim()
        val where = "$path:${id ?: "?"}"
        if (id == null || !ID_PATTERN.matches(id)) {
            problem(where, "bad id")
            return null
        }
        if (id in seen) {
            problem(where, "duplicate id")
            return null
        }
        val track = fixedTrack ?: enumOf<Track>(m.jStr("track"))
        val level = fixedLevel ?: enumOf<Level>(m.jStr("level"))
        val skill = enumOf<Skill>(m.jStr("skill"))
        if (track == null || level == null || skill == null) {
            problem(where, "bad track/level/skill")
            return null
        }
        val promptKo = m.jStr("promptKo").orEmpty().trim()
        val meaningKo = m.jStr("meaningKo").orEmpty().trim()
        val answers = m.jStrList("answers").map { it.trim() }.filter { it.isNotEmpty() }
        if (promptKo.isEmpty() || meaningKo.isEmpty() || answers.isEmpty()) {
            problem(where, "promptKo, meaningKo and answers are required")
            return null
        }
        val maxLoadName = m.jStr("maxLoad")
        val maxLoad = if (maxLoadName == null) LoadLevel.NORMAL else enumOf<LoadLevel>(maxLoadName)
        if (maxLoad == null || maxLoad !in ITEM_LOADS) {
            problem(where, "maxLoad must be PARKED, RELAXED or NORMAL")
            return null
        }
        val hookName = m.jStr("contextHook")
        val hook = if (hookName == null) defaultHook else enumOf<HookTrigger>(hookName)
        val pronName = m.jStr("pronFocus")
        val pron = enumOf<PronIssueType>(pronName)
        if ((hookName != null && hook == null) || (pronName != null && pron == null)) {
            problem(where, "bad contextHook/pronFocus")
            return null
        }
        var promptEn = m.optText("promptEn")
        val contrast = m.optText("contrast")
        when (skill) {
            Skill.MINIMAL_PAIR -> if (pron == null || contrast == null) {
                problem(where, "MINIMAL_PAIR needs pronFocus and contrast")
                return null
            }
            Skill.TOEIC_QA -> if (promptEn == null) {
                problem(where, "TOEIC_QA needs promptEn")
                return null
            }
            else -> Unit
        }
        // 따라 말하기·발음 짝은 들려줄 문장이 곧 정답
        if (promptEn == null && (skill == Skill.LISTEN_REPEAT || skill == Skill.MINIMAL_PAIR)) promptEn = answers.first()
        seen += id
        return LearningItem(
            id = id,
            track = track,
            level = level,
            unit = unit,
            skill = skill,
            promptKo = promptKo,
            promptEn = promptEn,
            answers = answers,
            keywords = m.jStrList("keywords").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            hintWord = m.optText("hintWord"),
            meaningKo = meaningKo,
            explanationKo = m.optText("explanationKo"),
            maxLoad = maxLoad,
            tags = m.jStrList("tags").map { it.trim() }.filter { it.isNotEmpty() },
            distractors = m.jStrList("distractors").map { it.trim() }.filter { it.isNotEmpty() },
            contextHook = hook,
            minWords = (m.jInt("minWords") ?: 0).coerceAtLeast(0),
            listenSec = m.jInt("listenSec")?.takeIf { it > 0 },
            contrast = contrast,
            pronFocus = pron,
        )
    }

    private fun problem(where: String, what: String) {
        problems += "$where: $what"
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z0-9]+(\\.[a-z0-9_]+)+$")
        val ITEM_LOADS = setOf(LoadLevel.PARKED, LoadLevel.RELAXED, LoadLevel.NORMAL)
        const val DEFAULT_HOOK_INTRO = "방금 상황에 딱 맞는 표현이에요."
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

/** 비어 있지 않은 문자열만 (없음·공백 → null) */
private fun Map<String, Any?>.optText(key: String): String? = jStr(key)?.trim()?.takeIf { it.isNotEmpty() }

private inline fun <reified E : Enum<E>> enumOf(name: String?): E? =
    if (name == null) null else enumValues<E>().firstOrNull { it.name == name }
