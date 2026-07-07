package dev.heyari.ari.actions

import dev.heyari.ari.actions.MediaTransportController.TransportPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPlanTest {
    private fun media(
        action: String, direction: String? = null, level: Int? = null, mute: Boolean? = null,
    ) = MediaAction(action, query = null, service = null, direction = direction, level = level, mute = mute)

    @Test
    fun volume_up_down_ignore_access_and_session() {
        assertEquals(TransportPlan.RaiseVolume,
            planMedia(media("volume", direction = "up"), hasAccess = false, hasActiveSession = false, maxVolume = 15))
        assertEquals(TransportPlan.LowerVolume,
            planMedia(media("volume", direction = "down"), hasAccess = false, hasActiveSession = false, maxVolume = 15))
    }

    @Test
    fun set_volume_maps_percentage_to_index() {
        assertEquals(TransportPlan.SetVolume(8),
            planMedia(media("volume", level = 50), hasAccess = false, hasActiveSession = false, maxVolume = 15))
    }

    @Test
    fun mute_unmute() {
        assertEquals(TransportPlan.Mute,
            planMedia(media("volume", mute = true), hasAccess = false, hasActiveSession = false, maxVolume = 15))
        assertEquals(TransportPlan.Unmute,
            planMedia(media("volume", mute = false), hasAccess = false, hasActiveSession = false, maxVolume = 15))
    }

    @Test
    fun transport_needs_permission_before_checking_session() {
        assertEquals(TransportPlan.NeedsPermission,
            planMedia(media("pause"), hasAccess = false, hasActiveSession = false, maxVolume = 15))
    }

    @Test
    fun transport_with_access_but_no_session_is_nothing_playing() {
        assertEquals(TransportPlan.NothingPlaying,
            planMedia(media("next"), hasAccess = true, hasActiveSession = false, maxVolume = 15))
    }

    @Test
    fun transport_with_access_and_session_carries_command() {
        assertEquals(TransportPlan.Transport("stop"),
            planMedia(media("stop"), hasAccess = true, hasActiveSession = true, maxVolume = 15))
        assertEquals(TransportPlan.Transport("resume"),
            planMedia(media("resume"), hasAccess = true, hasActiveSession = true, maxVolume = 15))
    }

    @Test
    fun unknown_action_fails() {
        val p = planMedia(media("teleport"), hasAccess = true, hasActiveSession = true, maxVolume = 15)
        assertEquals(TransportPlan.Failed("unknown media action teleport"), p)
    }

    @Test
    fun volume_without_any_field_fails() {
        val p = planMedia(media("volume"), hasAccess = true, hasActiveSession = true, maxVolume = 15)
        assertEquals(TransportPlan.Failed("volume with no direction/level/mute"), p)
    }
}
