package dev.heyari.ari.ui.settings.skills

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPermissionsTest {

    @Test
    fun locationCapabilityAsksForCoarseLocation() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            permissionsFor(listOf("location")),
        )
    }

    @Test
    fun capabilityNamesAreMatchedCaseInsensitively() {
        assertEquals(
            permissionsFor(listOf("location")),
            permissionsFor(listOf("Location")),
        )
    }

    @Test
    fun unmappedCapabilitiesContributeNothing() {
        // A skill declaring a capability this frontend doesn't map to a
        // runtime permission must still install rather than trip an error.
        assertTrue(permissionsFor(listOf("http", "storage_kv", "tts")).isEmpty())
    }

    @Test
    fun noCapabilitiesMeansNothingToAsk() {
        assertTrue(permissionsFor(emptyList()).isEmpty())
    }

    @Test
    fun sendMessageAsksForSms() {
        // The only true send on Android. Refusable — SMS then behaves like
        // every other service and opens the messaging app instead.
        assertEquals(
            listOf(Manifest.permission.SEND_SMS),
            permissionsFor(listOf("send_message")),
        )
    }

    @Test
    fun aSkillDeclaringSeveralMappedCapabilitiesAsksForAllOfThem() {
        // The message skill declares both. Before RequestMultiplePermissions
        // this silently asked for one and dropped the other.
        val wanted = permissionsFor(listOf("send_message", "contacts"))
        assertTrue(wanted.contains(Manifest.permission.SEND_SMS))
        assertTrue(wanted.contains(Manifest.permission.READ_CONTACTS))
    }

    @Test
    fun aPermissionSharedByTwoCapabilitiesIsAskedForOnce() {
        // The launcher takes an array; a duplicate would show the same
        // system dialog twice in a row.
        val doubled = permissionsFor(listOf("location", "location"))
        assertEquals(doubled.distinct(), doubled)
    }

    @Test
    fun everyMappedCapabilityDeclaresAtLeastOnePermission() {
        CAPABILITY_PERMISSIONS.forEach { (capability, permissions) ->
            assertTrue(
                "$capability maps to no permission — drop the entry instead",
                permissions.isNotEmpty(),
            )
        }
    }
}
