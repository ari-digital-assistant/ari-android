package dev.heyari.ari.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.ari_ffi.FfiLocationStatus

class LocationMappingTest {
    @Test fun no_permission_is_permission_denied() {
        val r = LocationLogic.resolve(hasPermission = false, servicesAvailable = false, fix = null)
        assertEquals(FfiLocationStatus.PERMISSION_DENIED, r.status)
        assertEquals(0.0, r.lat, 0.0)
    }

    @Test fun no_fix_is_timeout() {
        val r = LocationLogic.resolve(hasPermission = true, servicesAvailable = true, fix = null)
        assertEquals(FfiLocationStatus.TIMEOUT, r.status)
    }

    @Test fun fix_maps_to_ok_with_coords() {
        val fix = LocationLogic.Fix(lat = 35.8989, lon = 14.5146, accuracyM = 65.0, timeMs = 900L)
        val r = LocationLogic.resolve(hasPermission = true, servicesAvailable = true, fix = fix)
        assertEquals(FfiLocationStatus.OK, r.status)
        assertEquals(35.8989, r.lat, 0.0)
        assertEquals(14.5146, r.lon, 0.0)
        assertEquals(65.0, r.accuracyM, 0.0)
        assertEquals(900L, r.timestampMs)
    }

    @Test fun services_unavailable_is_unavailable() {
        val r = LocationLogic.resolve(hasPermission = true, servicesAvailable = false, fix = null)
        assertEquals(FfiLocationStatus.UNAVAILABLE, r.status)
        assertEquals(0.0, r.lat, 0.0)
    }

    @Test fun freshest_picks_the_newest_fix() {
        val old = LocationLogic.Fix(lat = 1.0, lon = 2.0, accuracyM = 10.0, timeMs = 100L)
        val new = LocationLogic.Fix(lat = 3.0, lon = 4.0, accuracyM = 900.0, timeMs = 500L)
        val mid = LocationLogic.Fix(lat = 5.0, lon = 6.0, accuracyM = 20.0, timeMs = 300L)
        assertEquals(new, LocationLogic.freshest(listOf(old, new, mid)))
    }

    @Test fun freshest_ignores_nulls() {
        val only = LocationLogic.Fix(lat = 1.0, lon = 2.0, accuracyM = 10.0, timeMs = 100L)
        assertEquals(only, LocationLogic.freshest(listOf(null, only, null)))
    }

    @Test fun freshest_of_nothing_is_null() {
        assertNull(LocationLogic.freshest(emptyList()))
        assertNull(LocationLogic.freshest(listOf(null, null)))
    }
}
