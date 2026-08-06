package dev.heyari.ari.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningPlaceTest {

    private val home = ListeningPlace(
        id = "home",
        name = "Home",
        latitude = 35.8997,
        longitude = 14.5147,
        radiusMetres = 150f,
    )

    @Test
    fun `places survive a JSON round trip`() {
        val original = listOf(
            home,
            ListeningPlace("work", "The office", 35.8833, 14.5000, 300f),
        )
        assertEquals(original, decodePlaces(encodePlaces(original)))
    }

    @Test
    fun `an empty or corrupt store decodes to nothing`() {
        assertEquals(emptyList<ListeningPlace>(), decodePlaces(null))
        assertEquals(emptyList<ListeningPlace>(), decodePlaces(""))
        assertEquals(emptyList<ListeningPlace>(), decodePlaces("{{{"))
    }

    @Test
    fun `a radius below the geofencing floor is raised to it`() {
        val raw = """[{"id":"a","name":"Tiny","lat":35.9,"lon":14.5,"radius":10.0}]"""
        assertEquals(ListeningPlace.MIN_RADIUS_METRES, decodePlaces(raw).single().radiusMetres)
    }

    @Test
    fun `an absurd radius is clamped to the maximum`() {
        val raw = """[{"id":"a","name":"Huge","lat":35.9,"lon":14.5,"radius":999999.0}]"""
        assertEquals(ListeningPlace.MAX_RADIUS_METRES, decodePlaces(raw).single().radiusMetres)
    }

    @Test
    fun `a missing radius falls back to the default`() {
        val raw = """[{"id":"a","name":"Plain","lat":35.9,"lon":14.5}]"""
        assertEquals(ListeningPlace.DEFAULT_RADIUS_METRES, decodePlaces(raw).single().radiusMetres)
    }

    @Test
    fun `entries without an id are dropped`() {
        val raw = """[{"name":"Nameless","lat":35.9,"lon":14.5,"radius":150.0}]"""
        assertEquals(emptyList<ListeningPlace>(), decodePlaces(raw))
    }

    @Test
    fun `entries without coordinates are dropped rather than geofenced at NaN`() {
        val raw = """[{"id":"a","name":"Nowhere","radius":150.0}]"""
        assertEquals(emptyList<ListeningPlace>(), decodePlaces(raw))
    }
}
