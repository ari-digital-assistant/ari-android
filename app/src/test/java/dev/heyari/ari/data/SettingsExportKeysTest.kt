package dev.heyari.ari.data

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExportKeysTest {

    /**
     * What stays on this phone when settings are exported, and why. A key
     * belongs either here or in SettingsRepository's exported lists.
     */
    private val leftBehind = setOf(
        // Wake tuning measured on this phone's microphone, and its history.
        "wake_ladder", "false_wake_log", "false_wake_notified_at",
        "ladder_tightened_at", "ladder_before_tightening", "ladder_tightened_model",
        // The contributor id is the credential for shared recordings.
        "contributor_id", "has_shared_recordings",
        // This install's identity and its bug reports' delete tokens.
        "install_id", "filed_bug_reports", "bug_report_fab_x", "bug_report_fab_y",
        // Wizard progress and the reminders it leaves behind.
        "onboarding_completed", "pending_cloud_assistant_setup", "pending_listening_setup",
        // Depends on how this phone runs Play Services.
        "place_fence_source",
    )

    private val declaredKeys: List<String> =
        SettingsRepository::class.java.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                (field.get(null) as Preferences.Key<*>).name
            }

    @Test
    fun `every setting is either exported or deliberately left behind`() {
        assertTrue("found no keys; has the companion moved?", declaredKeys.size > 40)
        val unclassified = declaredKeys.filter { SettingsRepository.exportedType(it) == null && it !in leftBehind }
        assertEquals("classify these in SettingsRepository or leftBehind", emptyList<String>(), unclassified)
    }

    @Test
    fun `nothing is both exported and left behind`() {
        assertEquals(emptyList<String>(), leftBehind.filter { SettingsRepository.exportedType(it) != null })
    }

    @Test
    fun `typed keys export as their own type`() {
        assertEquals(Boolean::class, SettingsRepository.exportedType("quiet_wake"))
        assertEquals(String::class, SettingsRepository.exportedType("listening_places"))
    }

    @Test
    fun `skill settings and per-model sensitivity export, per-model ladders do not`() {
        assertEquals(String::class, SettingsRepository.exportedType("assistant_config_dev.heyari.weather_units"))
        assertEquals(String::class, SettingsRepository.exportedType("wake_word_sensitivity_hey_ari_oww"))
        assertNull(SettingsRepository.exportedType("wake_ladder_hey_ari_oww"))
        assertNull(SettingsRepository.exportedType("contrib_watermark_wake-captures"))
    }
}
