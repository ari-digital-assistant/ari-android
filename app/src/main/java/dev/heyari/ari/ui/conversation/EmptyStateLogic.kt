package dev.heyari.ari.ui.conversation

// Only reliable name phrasings. The bare "i am" / "i'?m" alternatives were
// dropped — they false-positive on ordinary sentences ("i am hungry",
// "i'm tired"). "call me X" is kept (per product decision) even though it can
// still misfire on "call me later"; the other two are unambiguous.
private val NAME_PATTERNS = listOf(
    Regex("""(?:the user'?s name is|my name is|call me)\s+([\p{L}][\p{L}\-']{1,30})""", RegexOption.IGNORE_CASE),
    Regex("""(?:mi chiamo|il mio nome è|chiamami)\s+([\p{L}][\p{L}\-']{1,30})""", RegexOption.IGNORE_CASE),
)

/** Best-effort: scan freeform remembered facts for the user's name. First
 *  match wins; returns the name with its original casing, else null. */
fun detectUserName(facts: List<String>): String? {
    for (fact in facts) {
        for (p in NAME_PATTERNS) {
            p.find(fact)?.groups?.get(1)?.value?.let { return it }
        }
    }
    return null
}

enum class DayPart { MORNING, AFTERNOON, EVENING }

sealed interface GreetingModel {
    data class Named(val part: DayPart, val name: String) : GreetingModel
    object Anonymous : GreetingModel
}

fun greetingModel(name: String?, hourOfDay: Int): GreetingModel =
    if (name == null) GreetingModel.Anonymous
    else GreetingModel.Named(
        part = when (hourOfDay) { in 0..11 -> DayPart.MORNING; in 12..17 -> DayPart.AFTERNOON; else -> DayPart.EVENING },
        name = name,
    )

fun assembleChips(skillExamples: List<List<String>>, rememberNameChip: String?, max: Int): List<String> {
    val candidates = skillExamples.mapNotNull { it.firstOrNull() }
    return (listOfNotNull(rememberNameChip) + candidates).take(max)
}

enum class EmptyMode { FirstRun, SetUp }

/** Below this many installed skills, show the "browse skills" first-run face. */
private const val SKILL_THRESHOLD = 1

/**
 * Assistant skills (ChatGPT, Gemini, Claude) don't count: they answer
 * questions, which Ari does out of the box, so with nothing else installed Ari
 * is still bare and the first-run face still applies. [assistantIds] are the
 * assistant registry's ids, which for an installed assistant skill is its
 * skill id.
 */
fun emptyStateMode(installedSkillIds: List<String>, assistantIds: Set<String>): EmptyMode =
    if (installedSkillIds.count { it !in assistantIds } < SKILL_THRESHOLD) EmptyMode.FirstRun else EmptyMode.SetUp
