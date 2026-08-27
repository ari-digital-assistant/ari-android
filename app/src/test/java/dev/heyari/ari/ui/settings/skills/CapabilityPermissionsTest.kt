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
    fun sendMessageIsNotAskedForUntilAriIsTheDefaultAssistant() {
        // Play forbids prompting for SEND_SMS before the app holds the
        // assistant role. The skill still installs — SMS just hands off to the
        // messaging app like every other service.
        assertEquals(
            emptyList<String>(),
            requestablePermissions(listOf("send_message"), isDefaultAssistant = false),
        )
    }

    @Test
    fun sendMessageIsAskedForOnceAriIsTheDefaultAssistant() {
        assertEquals(
            listOf(Manifest.permission.SEND_SMS),
            requestablePermissions(listOf("send_message"), isDefaultAssistant = true),
        )
    }

    @Test
    fun theRoleGateDropsOnlyTheGatedPermission() {
        // A skill declaring both must still get its contacts prompt.
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            requestablePermissions(listOf("send_message", "contacts"), isDefaultAssistant = false),
        )
    }

    @Test
    fun ungatedCapabilitiesIgnoreTheAssistantRole() {
        assertEquals(
            requestablePermissions(listOf("location"), isDefaultAssistant = false),
            requestablePermissions(listOf("location"), isDefaultAssistant = true),
        )
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
