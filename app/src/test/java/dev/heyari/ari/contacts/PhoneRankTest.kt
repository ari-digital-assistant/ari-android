package dev.heyari.ari.contacts

import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering that decides which of a contact's numbers gets the text.
 *
 * These are exact ranks rather than "is better than" assertions because the
 * comparison in `phoneFor` is a plain `<` — a tie would silently keep whichever
 * row the provider returned first, which is the bug this replaced.
 */
class PhoneRankTest {

    private fun rank(superPrimary: Boolean = false, primary: Boolean = false, type: Int = Phone.TYPE_OTHER) =
        ContactsProvider.phoneRank(superPrimary, primary, type)

    @Test
    fun theContactsDefaultNumberWinsOutright() {
        assertEquals(0, rank(superPrimary = true))
        // Even when it isn't a mobile: the user said this is the one.
        assertEquals(0, rank(superPrimary = true, type = Phone.TYPE_HOME))
    }

    @Test
    fun anAccountsPrimaryComesNext() {
        assertEquals(1, rank(primary = true))
        assertEquals(1, rank(primary = true, type = Phone.TYPE_WORK))
    }

    @Test
    fun aMobileBeatsAnythingElseUnmarked() {
        assertEquals(2, rank(type = Phone.TYPE_MOBILE))
        assertEquals(3, rank(type = Phone.TYPE_HOME))
        assertEquals(3, rank(type = Phone.TYPE_WORK))
        assertEquals(3, rank(type = Phone.TYPE_OTHER))
    }

    @Test
    fun superPrimaryOutranksPrimary() {
        assertTrue(rank(superPrimary = true, primary = true) < rank(primary = true))
    }

    @Test
    fun theRealCaseThatLostAText() {
        // A contact with a Maltese "Other" number and a UK mobile. The old code
        // took whichever row came back first and the text went to the landline.
        val other = rank(type = Phone.TYPE_OTHER)
        val mobile = rank(type = Phone.TYPE_MOBILE)
        assertTrue("a mobile must beat an Other number", mobile < other)
    }

    @Test
    fun everyRankIsDistinctSoThereIsNothingToTieBreak() {
        val ranks = listOf(
            rank(superPrimary = true),
            rank(primary = true),
            rank(type = Phone.TYPE_MOBILE),
            rank(type = Phone.TYPE_OTHER),
        )
        assertEquals(listOf(0, 1, 2, 3), ranks)
    }
}
