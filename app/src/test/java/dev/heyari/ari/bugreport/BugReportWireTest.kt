package dev.heyari.ari.bugreport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BugReportWireTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun report(
        privateNote: String? = null,
        stackTrace: String? = null,
        setup: SetupInfo = SetupInfo(),
        device: DeviceInfo = DeviceInfo(model = "Pixel 8", androidVersion = "16"),
        skills: List<SkillInfo> = emptyList(),
        attachments: List<BugAttachment> = emptyList(),
    ) = BugReport(
        installId = "a3f1c9d2-4e5b-4a71-9f00-1c2d3e4f5a6b",
        description = "Ari couldn't reach Home Assistant",
        privateNote = privateNote,
        stackTrace = stackTrace,
        app = AppInfo(version = "0.9.3", buildType = "beta", commit = "a1b2c3d", locale = "en"),
        setup = setup,
        device = device,
        skills = skills,
        attachments = attachments,
    )

    private fun attachment(kind: AttachmentKind, size: Int): BugAttachment {
        val file = temp.newFile(kind.wireName)
        file.writeBytes(ByteArray(size))
        return BugAttachment(kind, file)
    }

    @Test
    fun carries_the_fields_the_server_requires() {
        val json = report().toWireJson()
        assertEquals("a3f1c9d2-4e5b-4a71-9f00-1c2d3e4f5a6b", json.getString("installId"))
        assertEquals("Ari couldn't reach Home Assistant", json.getString("description"))
        assertEquals("0.9.3", json.getJSONObject("app").getString("version"))
        assertEquals("Pixel 8", json.getJSONObject("device").getString("model"))
        assertEquals("16", json.getJSONObject("device").getString("androidVersion"))
    }

    @Test
    fun omits_absent_fields_rather_than_sending_blanks() {
        val json = report().toWireJson()
        assertFalse(json.has("privateNote"))
        assertFalse(json.has("stackTrace"))
        val device = json.getJSONObject("device")
        assertFalse(device.has("ramFreeMb"))
        assertFalse(device.has("batteryExempt"))
        assertFalse(json.getJSONObject("setup").has("assistant"))
    }

    @Test
    fun treats_a_blank_private_note_as_no_note() {
        assertFalse(report(privateNote = "   ").toWireJson().has("privateNote"))
        assertTrue(report(privateNote = "my HA host").toWireJson().has("privateNote"))
    }

    @Test
    fun sends_false_for_battery_exempt_rather_than_dropping_it() {
        // Absent means unknown and the issue says so. Actually being outside
        // the exemption is a finding, not a gap, so it has to survive.
        val json = report(
            device = DeviceInfo(model = "Pixel 8", androidVersion = "16", batteryExempt = false),
        ).toWireJson()
        assertTrue(json.getJSONObject("device").has("batteryExempt"))
        assertFalse(json.getJSONObject("device").getBoolean("batteryExempt"))
    }

    @Test
    fun sends_a_zero_measurement_rather_than_dropping_it() {
        val json = report(
            device = DeviceInfo(model = "P", androidVersion = "16", storageFreeMb = 0),
        ).toWireJson()
        assertEquals(0, json.getJSONObject("device").getInt("storageFreeMb"))
    }

    @Test
    fun keeps_the_private_note_out_of_every_public_field() {
        val json = report(privateNote = "my HA is at ha.example.internal").toWireJson()
        assertFalse(json.getString("description").contains("ha.example"))
        assertFalse(json.getJSONObject("device").toString().contains("ha.example"))
        assertEquals("my HA is at ha.example.internal", json.getString("privateNote"))
    }

    @Test
    fun declares_each_attachment_by_wire_name_and_real_size() {
        val json = report(
            attachments = listOf(
                attachment(AttachmentKind.LOGCAT, 1234),
                attachment(AttachmentKind.WAKE_AUDIO, 4096),
            ),
        ).toWireJson()
        val list = json.getJSONArray("attachments")
        assertEquals(2, list.length())
        assertEquals("logcat", list.getJSONObject(0).getString("kind"))
        assertEquals(1234L, list.getJSONObject(0).getLong("bytes"))
        assertEquals("wake-audio", list.getJSONObject(1).getString("kind"))
        assertEquals(4096L, list.getJSONObject(1).getLong("bytes"))
    }

    @Test
    fun sends_an_empty_attachment_list_when_consent_was_withheld() {
        val list = report().toWireJson().getJSONArray("attachments")
        assertEquals(0, list.length())
    }

    @Test
    fun every_wire_name_matches_what_the_server_accepts() {
        // Kept in step with ATTACHMENT_KINDS in ari-web's report.mjs. A rename
        // on either side is a 400 the app cannot recover from.
        assertEquals(
            listOf("logcat", "screenshot", "conversation", "commands", "wake-audio", "all-audio"),
            AttachmentKind.entries.map { it.wireName },
        )
    }

    @Test
    fun content_types_match_the_extensions_the_server_stores_under() {
        assertEquals("text/plain", AttachmentKind.LOGCAT.contentType)
        assertEquals("image/png", AttachmentKind.SCREENSHOT.contentType)
        assertEquals("application/json", AttachmentKind.CONVERSATION.contentType)
        assertEquals("application/zip", AttachmentKind.ALL_AUDIO.contentType)
    }

    @Test
    fun skills_carry_a_version_and_survive_one_that_is_missing() {
        val json = report(
            skills = listOf(
                SkillInfo("dev.heyari.home-assistant", "0.4.1"),
                SkillInfo("dev.heyari.sideloaded", null),
            ),
        ).toWireJson().getJSONArray("skills")
        assertEquals("0.4.1", json.getJSONObject(0).getString("version"))
        assertFalse(json.getJSONObject(1).has("version"))
    }

    @Test
    fun permissions_are_always_an_array_even_when_none_are_granted() {
        val json = report().toWireJson()
        assertEquals(0, json.getJSONObject("device").getJSONArray("permissions").length())
    }

    @Test
    fun the_whole_body_stays_well_under_the_servers_cap() {
        val json = report(
            stackTrace = "\tat dev.heyari.ari.voice.VoiceSession.stop(VoiceSession.kt:187)\n".repeat(60),
            skills = (1..20).map { SkillInfo("dev.heyari.skill$it", "1.0.0") },
            device = DeviceInfo(
                model = "Pixel 8",
                androidVersion = "16",
                fingerprint = "google/shiba/shiba:16/BP41.250725.006/13456789:user/release-keys",
                permissions = listOf("mic", "notifications", "location", "background-location"),
            ),
        ).toWireJson()
        // MAX_BODY_BYTES on the server is 64 KiB.
        assertTrue(json.toString().toByteArray().size < 64 * 1024)
    }

    @Test
    fun parses_a_creation_response_into_uploads_matched_to_their_files() {
        // The shape ari-web actually returns, so a change on either side of
        // this contract fails here rather than on a device.
        val response = JSONObject(
            """
            {"reportId":"r_abc","deleteToken":"tok","uploadUrlExpiresIn":900,
             "uploads":[{"kind":"logcat","contentType":"text/plain","url":"https://s3/put"}]}
            """.trimIndent(),
        )
        assertEquals("r_abc", response.getString("reportId"))
        assertEquals("tok", response.getString("deleteToken"))
        val upload = response.getJSONArray("uploads").getJSONObject(0)
        assertEquals("logcat", upload.getString("kind"))
        assertEquals(AttachmentKind.LOGCAT.contentType, upload.getString("contentType"))
    }
}
