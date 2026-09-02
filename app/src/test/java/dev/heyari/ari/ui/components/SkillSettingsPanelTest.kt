package dev.heyari.ari.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ari_ffi.FfiConfigField

/**
 * Covers the value a skill-backed settings query / action actually sends
 * for its `depends_on` siblings.
 *
 * Regression origin: tapping Home Assistant's "Sign in" button right
 * after typing the server URL sent an empty `base_url`, because the
 * payload was built from the last-persisted `currentValue` while the
 * typed text still lived only in the text field's local state. The skill
 * correctly answered "not configured", which read as a lie to the user.
 */
class SkillSettingsPanelTest {

    private fun field(
        key: String,
        currentValue: String? = null,
        defaultValue: String? = null,
        keyboard: String? = null,
    ) = FfiConfigField(
        key = key,
        label = key,
        fieldType = "text",
        required = false,
        defaultValue = defaultValue,
        currentValue = currentValue,
        options = emptyList(),
        showWhenKey = null,
        showWhenEquals = emptyList(),
        dependsOn = emptyList(),
        validate = false,
        helpText = null,
        collapsedGroup = null,
        keyboard = keyboard,
    )

    @Test
    fun `url hint gives a URI keyboard with no autocorrect`() {
        val opts = field("base_url", keyboard = "url").keyboardOptions()
        assertEquals(KeyboardType.Uri, opts.keyboardType)
        assertEquals(false, opts.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, opts.capitalization)
    }

    @Test
    fun `absent or unknown hint falls back to the plain text keyboard`() {
        // A manifest written against a newer schema must still render here,
        // so an unrecognised hint degrades rather than being rejected.
        assertEquals(KeyboardOptions.Default, field("base_url").keyboardOptions())
        assertEquals(
            KeyboardOptions.Default,
            field("base_url", keyboard = "telephone").keyboardOptions(),
        )
    }

    @Test
    fun `draft beats the persisted value`() {
        val byKey = mapOf("base_url" to field("base_url", currentValue = "https://old.example"))
        val drafts = mapOf("base_url" to "https://new.example")
        assertEquals(
            "https://new.example",
            effectiveFieldValue("base_url", byKey, drafts),
        )
    }

    @Test
    fun `draft beats an unset persisted value`() {
        val byKey = mapOf("base_url" to field("base_url"))
        val drafts = mapOf("base_url" to "ha.vassallo.cloud")
        assertEquals(
            "ha.vassallo.cloud",
            effectiveFieldValue("base_url", byKey, drafts),
        )
    }

    @Test
    fun `no draft falls back to the persisted value`() {
        val byKey = mapOf("base_url" to field("base_url", currentValue = "https://stored.example"))
        assertEquals(
            "https://stored.example",
            effectiveFieldValue("base_url", byKey, emptyMap()),
        )
    }

    @Test
    fun `no draft and no persisted value falls back to the default`() {
        val byKey = mapOf("mode" to field("mode", defaultValue = "auto"))
        assertEquals("auto", effectiveFieldValue("mode", byKey, emptyMap()))
    }

    @Test
    fun `persisted value beats the default`() {
        val byKey = mapOf("mode" to field("mode", currentValue = "manual", defaultValue = "auto"))
        assertEquals("manual", effectiveFieldValue("mode", byKey, emptyMap()))
    }

    @Test
    fun `a cleared field reports empty rather than the stale persisted value`() {
        val byKey = mapOf("base_url" to field("base_url", currentValue = "https://stored.example"))
        val drafts = mapOf("base_url" to "")
        assertEquals("", effectiveFieldValue("base_url", byKey, drafts))
    }

    @Test
    fun `unknown key resolves to empty`() {
        assertEquals("", effectiveFieldValue("nope", emptyMap(), emptyMap()))
    }

    @Test
    fun `dependency payload uses drafts for every declared dependency`() {
        val byKey = mapOf(
            "base_url" to field("base_url", currentValue = "https://stored.example"),
            "token" to field("token"),
        )
        val drafts = mapOf("base_url" to "https://typed.example", "token" to "abc123")
        assertEquals(
            mapOf("base_url" to "https://typed.example", "token" to "abc123"),
            dependencyValues(listOf("base_url", "token"), byKey, drafts),
        )
    }
}
