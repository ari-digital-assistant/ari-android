package dev.heyari.ari.actions

import dev.heyari.ari.data.card.Card
import dev.heyari.ari.data.card.CardAction
import dev.heyari.ari.notifications.AlertSpec
import dev.heyari.ari.notifications.NotificationPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SKILL_ID = "dev.heyari.timer"

class PresentationEnvelopeParseTest {

    @Test
    fun rejectsMissingVersion() {
        // No `v` field → 0 → mismatch with SUPPORTED_VERSION 1.
        assertNull(PresentationEnvelope.parse(JSONObject("""{}"""), SKILL_ID))
    }

    @Test
    fun rejectsWrongVersion() {
        assertNull(PresentationEnvelope.parse(JSONObject("""{"v":2}"""), SKILL_ID))
    }

    @Test
    fun parsesMinimalEnvelope() {
        val env = PresentationEnvelope.parse(JSONObject("""{"v":1}"""), SKILL_ID)
        assertNotNull(env)
        assertEquals(null, env!!.speak)
        assertTrue(env.cards.isEmpty())
        assertTrue(env.alerts.isEmpty())
        assertTrue(env.notifications.isEmpty())
        assertNull(env.launchApp)
        assertNull(env.search)
        assertFalse(env.hasPresentationPrimitives())
    }

    @Test
    fun parsesSpeakOnly() {
        val env = PresentationEnvelope.parse(
            JSONObject("""{"v":1,"speak":"hello"}"""), SKILL_ID,
        )!!
        assertEquals("hello", env.speak)
    }

    @Test
    fun parsesLaunchApp() {
        val env = PresentationEnvelope.parse(
            JSONObject("""{"v":1,"launch_app":"Spotify"}"""), SKILL_ID,
        )!!
        assertEquals("Spotify", env.launchApp)
    }

    @Test
    fun parsesSearch() {
        val env = PresentationEnvelope.parse(
            JSONObject("""{"v":1,"search":"capital of malta"}"""), SKILL_ID,
        )!!
        assertEquals("capital of malta", env.search)
    }

    @Test
    fun parsesClipboard() {
        val env = PresentationEnvelope.parse(
            JSONObject("""{"v":1,"clipboard":{"text":"copied"}}"""), SKILL_ID,
        )!!
        assertEquals("copied", env.clipboardText)
    }

    @Test
    fun parsesCardWithCountdownAndOnComplete() {
        val src = """
            {
              "v": 1,
              "speak": "Pasta timer set for 8 minutes.",
              "cards": [{
                "id": "card_pasta",
                "title": "Pasta timer",
                "subtitle": "Started just now",
                "icon": "asset:icons/timer.png",
                "countdown_to_ts_ms": 1000480000,
                "started_at_ts_ms": 1000000000,
                "accent": "default",
                "actions": [
                  {"id":"cancel","label":"Cancel","utterance":"cancel my pasta timer"}
                ],
                "on_complete": {
                  "alert": {
                    "id":"alert_pasta",
                    "title":"Pasta timer done",
                    "urgency":"critical",
                    "sound":"asset:timer_ding.wav",
                    "speech_loop":"Pasta timer",
                    "auto_stop_ms":120000,
                    "max_cycles":12,
                    "full_takeover":true,
                    "icon":"asset:icons/timer.png",
                    "actions":[{"id":"stop_alert","label":"Stop","style":"primary"}]
                  },
                  "dismiss_card": true,
                  "dismiss_notifications": ["notif_pasta"]
                }
              }]
            }
        """.trimIndent()
        val env = PresentationEnvelope.parse(JSONObject(src), SKILL_ID)!!
        assertEquals(1, env.cards.size)
        val card = env.cards[0]
        assertEquals("card_pasta", card.id)
        assertEquals(SKILL_ID, card.skillId)
        assertEquals("Pasta timer", card.title)
        assertEquals("asset:icons/timer.png", card.icon)
        assertEquals(1000480000L, card.countdownToTsMs)
        assertEquals(1000000000L, card.startedAtTsMs)
        assertEquals(Card.Accent.DEFAULT, card.accent)
        assertEquals(1, card.actions.size)
        assertEquals(CardAction.Style.DEFAULT, card.actions[0].style)
        assertEquals("cancel my pasta timer", card.actions[0].utterance)
        // on_complete.alert
        val alert = card.onComplete!!.alert!!
        assertEquals("alert_pasta", alert.id)
        assertEquals(SKILL_ID, alert.skillId)
        assertEquals(AlertSpec.Urgency.CRITICAL, alert.urgency)
        assertEquals("asset:timer_ding.wav", alert.sound)
        assertEquals("Pasta timer", alert.speechLoop)
        assertEquals(120_000L, alert.autoStopMs)
        assertEquals(12, alert.maxCycles)
        assertTrue(alert.fullTakeover)
        assertEquals("asset:icons/timer.png", alert.icon)
        assertTrue(card.onComplete.dismissCard)
        assertEquals(listOf("notif_pasta"), card.onComplete.dismissNotificationIds)
    }

    @Test
    fun onCompleteDismissNotificationsDefaultsToEmpty() {
        // When the field is absent in the wire format, the parsed list is
        // empty (not null). Receiver-side iteration is safe either way.
        val src = """
            {"v":1,"cards":[{"id":"c","title":"t","countdown_to_ts_ms":1000,
              "on_complete":{"dismiss_card":true}}]}
        """.trimIndent()
        val env = PresentationEnvelope.parse(JSONObject(src), SKILL_ID)!!
        val oc = env.cards[0].onComplete!!
        assertEquals(emptyList<String>(), oc.dismissNotificationIds)
    }

    @Test
    fun parsesNotificationWithCountdown() {
        val src = """
            {
              "v": 1,
              "notifications": [{
                "id":"notif_pasta",
                "title":"Pasta timer",
                "body":"Running…",
                "importance":"default",
                "sticky":true,
                "countdown_to_ts_ms":1000480000,
                "actions":[{"id":"cancel","label":"Cancel","utterance":"cancel my pasta timer"}]
              }]
            }
        """.trimIndent()
        val env = PresentationEnvelope.parse(JSONObject(src), SKILL_ID)!!
        assertEquals(1, env.notifications.size)
        val n = env.notifications[0]
        assertEquals("notif_pasta", n.id)
        assertEquals(NotificationPrimitive.Importance.DEFAULT, n.importance)
        assertTrue(n.sticky)
        assertEquals(1000480000L, n.countdownToTsMs)
        assertEquals(1, n.actions.size)
        assertEquals("cancel my pasta timer", n.actions[0].utterance)
    }

    @Test
    fun parsesDismissals() {
        val src = """
            {
              "v": 1,
              "dismiss": {
                "cards": ["card_a","card_b"],
                "notifications": ["notif_a"],
                "alerts": ["alert_a"]
              }
            }
        """.trimIndent()
        val env = PresentationEnvelope.parse(JSONObject(src), SKILL_ID)!!
        assertEquals(listOf("card_a", "card_b"), env.dismissCardIds)
        assertEquals(listOf("notif_a"), env.dismissNotificationIds)
        assertEquals(listOf("alert_a"), env.dismissAlertIds)
        assertTrue(env.hasPresentationPrimitives())
    }

    @Test
    fun cardWithoutIdIsSkipped() {
        // Defensive: skill emits a malformed card. We drop it rather than
        // crash the whole envelope.
        val src = """
            {"v":1,"cards":[{"title":"no id"},{"id":"good","title":"OK"}]}
        """.trimIndent()
        val env = PresentationEnvelope.parse(JSONObject(src), SKILL_ID)!!
        assertEquals(1, env.cards.size)
        assertEquals("good", env.cards[0].id)
    }

    @Test
    fun nullableFieldsTolerateAbsentAndNullJson() {
        // body explicitly null AND absent should both yield kotlin null.
        val withNull = PresentationEnvelope.parse(
            JSONObject("""{"v":1,"cards":[{"id":"c","title":"t","body":null}]}"""),
            SKILL_ID,
        )!!
        assertNull(withNull.cards[0].body)

        val withMissing = PresentationEnvelope.parse(
            JSONObject("""{"v":1,"cards":[{"id":"c","title":"t"}]}"""),
            SKILL_ID,
        )!!
        assertNull(withMissing.cards[0].body)
    }

    @Test
    fun stampsSkillIdOnEveryNestedPrimitive() {
        // The envelope JSON doesn't carry skill id; the parser threads
        // `skillId` through so all nested primitives can later resolve
        // their asset references back to this skill's bundle dir.
        val src = """
            {"v":1,
             "cards":[{"id":"c","title":"t",
                "on_complete":{"alert":{"id":"a","title":"A","urgency":"high","sound":"system.alarm"}}}],
             "notifications":[{"id":"n","title":"N"}],
             "alerts":[{"id":"a2","title":"A2","urgency":"normal","sound":"system.notification"}]}
        """.trimIndent()
        val env = PresentationEnvelope.parse(JSONObject(src), "dev.heyari.demo")!!
        assertEquals("dev.heyari.demo", env.cards[0].skillId)
        assertEquals("dev.heyari.demo", env.cards[0].onComplete!!.alert!!.skillId)
        assertEquals("dev.heyari.demo", env.notifications[0].skillId)
        assertEquals("dev.heyari.demo", env.alerts[0].skillId)
    }
}
