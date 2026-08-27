package dev.heyari.ari.reporting

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBodyTest {

    private fun data(vararg pairs: Pair<String, String?>): Data =
        Data.Builder().apply { pairs.forEach { (k, v) -> putString(k, v) } }.build()

    private val minimal = arrayOf(
        ReportSender.KEY_CATEGORY to "offensive",
        ReportSender.KEY_TEXT to "something Ari said",
    )

    @Test
    fun theMinimumIsCategoryAndText() {
        val body = ReportWorker.buildBody(data(*minimal))!!
        assertEquals("offensive", body.getString("category"))
        assertEquals("something Ari said", body.getString("text"))
    }

    @Test
    fun kindDefaultsToAResponseReport() {
        // A malformed work request should not silently become a skill report,
        // which the email labels quite differently.
        assertEquals("response", ReportWorker.buildBody(data(*minimal))!!.getString("kind"))
    }

    @Test
    fun aSkillReportKeepsItsKind() {
        val body = ReportWorker.buildBody(
            data(*minimal, ReportSender.KEY_KIND to ReportKind.SKILL.wireName)
        )!!
        assertEquals("skill", body.getString("kind"))
    }

    @Test
    fun everyKindHasADistinctWireName() {
        val names = ReportKind.entries.map { it.wireName }
        assertEquals(listOf("response", "skill"), names)
    }

    @Test
    fun noTextMeansNothingWorthSending() {
        // Returns null so the worker fails rather than retries — the server
        // would refuse it identically every time.
        assertNull(ReportWorker.buildBody(data(ReportSender.KEY_CATEGORY to "offensive")))
        assertNull(ReportWorker.buildBody(data(*minimal, ReportSender.KEY_TEXT to "   ")))
    }

    @Test
    fun aMissingCategoryFallsBackToOther() {
        // The dialog always sets one; this is only reachable from a malformed
        // work request, and "other" is the honest default.
        val body = ReportWorker.buildBody(data(ReportSender.KEY_TEXT to "x"))!!
        assertEquals("other", body.getString("category"))
    }

    @Test
    fun withheldFieldsAreOmittedNotBlank() {
        // The email prints "(not recorded)" for an absent field. An empty
        // string would render as a present-but-empty value instead.
        val body = ReportWorker.buildBody(data(*minimal))!!
        assertFalse(body.has("prompt"))
        assertFalse(body.has("note"))
        assertFalse(body.has("skillId"))
    }

    @Test
    fun blankFieldsAreTreatedAsWithheld() {
        val body = ReportWorker.buildBody(
            data(*minimal, ReportSender.KEY_PROMPT to "  ", ReportSender.KEY_NOTE to "")
        )!!
        assertFalse(body.has("prompt"))
        assertFalse(body.has("note"))
    }

    @Test
    fun everyOptionalFieldSurvivesWhenSent() {
        val body = ReportWorker.buildBody(
            data(
                *minimal,
                ReportSender.KEY_PROMPT to "what the user asked",
                ReportSender.KEY_NOTE to "this was unpleasant",
                ReportSender.KEY_SKILL_ID to "dev.heyari.timer",
                ReportSender.KEY_APP_VERSION to "0.1.0",
            )
        )!!
        assertEquals("what the user asked", body.getString("prompt"))
        assertEquals("this was unpleasant", body.getString("note"))
        assertEquals("dev.heyari.timer", body.getString("skillId"))
        assertEquals("0.1.0", body.getString("appVersion"))
    }

    @Test
    fun everyCategoryHasADistinctWireName() {
        // The server rejects anything outside its own list, so a duplicate or a
        // typo here is a report that silently never arrives.
        val names = ReportCategory.entries.map { it.wireName }
        assertEquals(names.size, names.toSet().size)
        assertEquals(listOf("offensive", "harmful", "wrong", "other"), names)
    }

    @Test
    fun theBodyIsJsonWithNoUnexpectedFields() {
        // Nothing about the device or the user should be able to creep in.
        val body = ReportWorker.buildBody(
            data(*minimal, ReportSender.KEY_APP_VERSION to "0.1.0")
        )!!
        val keys = body.keys().asSequence().toSet()
        assertEquals(setOf("kind", "category", "text", "appVersion"), keys)
        assertTrue(body.toString().startsWith("{"))
    }
}
