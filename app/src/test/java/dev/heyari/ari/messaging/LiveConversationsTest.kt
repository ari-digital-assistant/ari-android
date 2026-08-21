package dev.heyari.ari.messaging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter and the TTL are the privacy guarantees, so they're tested as
 * such: a banking notification must never become an entry, and an entry must
 * not outlive the conversation it belongs to.
 */
class LiveConversationsTest {

    private val catalogue: Map<String, MessagingService> by lazy {
        MessagingServices.parse(File("src/main/assets/messaging-services.json").readText())
    }

    @Test
    fun aMessagingPackageIsRecognised() {
        val s = LiveConversations.serviceFor("com.whatsapp", catalogue)
        assertNotNull(s)
        assertEquals("whatsapp", s!!.id)
    }

    @Test
    fun everythingElseIsDroppedBeforeItIsEverRead() {
        // The whole privacy stance in one assertion: a listener sees banking,
        // health and 2FA notifications, and none of them can become an entry.
        for (pkg in listOf(
            "com.revolut.revolut",
            "com.google.android.apps.authenticator2",
            "com.nhs.online.nhsonline",
            "com.android.systemui",
        )) {
            assertNull(pkg, LiveConversations.serviceFor(pkg, catalogue))
        }
    }

    @Test
    fun aSchemeAddressedServiceMatchesNoPackage() {
        // Email has no packages, so it can never claim somebody else's
        // notification by accident.
        assertNull(LiveConversations.serviceFor("", catalogue))
    }

    @Test
    fun aConversationGoesStaleAfterTheTtl() {
        val posted = 1_000_000L
        assertFalse(LiveConversations.isStale(posted, posted + LiveConversations.TTL_MS - 1))
        assertTrue(LiveConversations.isStale(posted, posted + LiveConversations.TTL_MS + 1))
    }

    @Test
    fun aFreshConversationIsNotStale() {
        assertFalse(LiveConversations.isStale(1_000_000L, 1_000_000L))
    }

    @Test
    fun theTtlIsShortEnoughToBeDefensible() {
        // A PendingIntent for a thread the user has mentally closed is a reply
        // waiting to go somewhere wrong. An hour would be too long.
        assertTrue(LiveConversations.TTL_MS <= 60 * 60 * 1000)
    }

    // --- choosing which thread a spoken name meant ---

    private fun choose(name: String?, vararg titles: String) =
        LiveConversations.choose(name, titles.toList())

    @Test
    fun noNameTakesTheNewestThread() {
        // "reply, on my way" while driving — the case the whole feature is for.
        assertEquals(LiveConversations.Choice.One(0), choose(null, "Gail", "Mario"))
        assertEquals(LiveConversations.Choice.One(0), choose("  ", "Gail", "Mario"))
    }

    @Test
    fun aNameFindsItsThread() {
        assertEquals(LiveConversations.Choice.One(1), choose("mario", "Gail", "Mario"))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(LiveConversations.Choice.One(0), choose("GAIL", "Gail Borg"))
    }

    @Test
    fun anExactTitleBeatsAPartialOne() {
        assertEquals(LiveConversations.Choice.One(1), choose("gail", "Gail Marie", "Gail"))
    }

    @Test
    fun twoPlausibleThreadsAreRefusedNotGuessed() {
        val picked = choose("gail", "Gail Marie", "Gail Borg")
        assertTrue(picked is LiveConversations.Choice.Several)
        assertEquals(2, (picked as LiveConversations.Choice.Several).titles.size)
    }

    @Test
    fun aNameNobodyMatchesFindsNothing() {
        assertEquals(LiveConversations.Choice.None, choose("gail", "Mario", "Sam"))
    }

    @Test
    fun noLiveThreadsMeansNothingToChooseFrom() {
        assertEquals(LiveConversations.Choice.None, choose(null))
        assertEquals(LiveConversations.Choice.None, choose("gail"))
    }
}