package dev.heyari.ari.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsExportFormatTest {

    private val export = SettingsExport(
        settings = mapOf(
            "stt_mode" to "openai",
            "quiet_wake" to false,
            "personality" to "Dry, \"brief\", British.",
            "assistant_config_dev.heyari.weather_units" to "metric",
        ),
        autoUpdate = true,
        autoUpdateOnMetered = false,
        skills = listOf("dev.heyari.reminder", "dev.heyari.weather"),
    )

    @Test
    fun `an export reads back as written`() {
        assertEquals(export, parseExport(encodeExport(export)))
    }

    @Test
    fun `a file that is not JSON is not an export`() {
        assertEquals(ImportOutcome.NotAnExport, parseExport("PK\u0003\u0004 a zip, say"))
    }

    @Test
    fun `JSON without the format marker is not an export`() {
        assertEquals(ImportOutcome.NotAnExport, parseExport("""{"version": 1, "settings": {}}"""))
        assertEquals(ImportOutcome.NotAnExport, parseExport("""{"format": "ari-settings"}"""))
        assertEquals(ImportOutcome.NotAnExport, parseExport("""{"format": "ari-settings", "version": 0}"""))
    }

    @Test
    fun `a later format is refused rather than half read`() {
        assertEquals(ImportOutcome.FromNewerAri, parseExport("""{"format": "ari-settings", "version": 2}"""))
    }

    @Test
    fun `values that are neither text nor switches are dropped`() {
        val parsed = parseExport(
            """{"format": "ari-settings", "version": 1, "settings": {"stt_mode": "openai", "bug_report_fab_x": 0.5, "x": {}, "y": null}}""",
        )
        assertEquals(
            SettingsExport(mapOf("stt_mode" to "openai"), autoUpdate = null, autoUpdateOnMetered = null, skills = emptyList()),
            parsed,
        )
    }

    @Test
    fun `missing sections read as empty, and only text skill ids count`() {
        val parsed = parseExport("""{"format": "ari-settings", "version": 1, "skills": ["dev.heyari.weather", 7]}""")
        assertEquals(
            SettingsExport(emptyMap(), autoUpdate = null, autoUpdateOnMetered = null, skills = listOf("dev.heyari.weather")),
            parsed,
        )
    }
}
