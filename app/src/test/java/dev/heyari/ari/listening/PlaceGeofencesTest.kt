package dev.heyari.ari.listening

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceGeofencesTest {

    private fun home(distance: Float, accuracy: Float) = PlaceReading("home", 100f, distance, accuracy)

    @Test
    fun `a fix wholly inside a place enters it`() {
        assertEquals(setOf("home"), insideAfterFix(emptySet(), listOf(home(distance = 40f, accuracy = 60f))))
    }

    @Test
    fun `a fix wholly outside a place leaves it`() {
        assertEquals(emptySet<String>(), insideAfterFix(setOf("home"), listOf(home(distance = 170f, accuracy = 60f))))
    }

    @Test
    fun `a fix straddling the edge changes nothing either way`() {
        val straddling = listOf(home(distance = 90f, accuracy = 60f))
        assertEquals(setOf("home"), insideAfterFix(setOf("home"), straddling))
        assertEquals(emptySet<String>(), insideAfterFix(emptySet(), straddling))
    }

    @Test
    fun `the boundary itself counts as inside and only beyond it as outside`() {
        assertEquals(setOf("home"), insideAfterFix(emptySet(), listOf(home(distance = 40f, accuracy = 60f))))
        assertEquals(setOf("home"), insideAfterFix(setOf("home"), listOf(home(distance = 160f, accuracy = 60f))))
        assertEquals(emptySet<String>(), insideAfterFix(setOf("home"), listOf(home(distance = 160.5f, accuracy = 60f))))
    }

    @Test
    fun `each place is decided on its own reading`() {
        val readings = listOf(
            home(distance = 10f, accuracy = 6f),
            PlaceReading("work", 150f, distanceMetres = 4000f, accuracyMetres = 6f),
            PlaceReading("gym", 100f, distanceMetres = 98f, accuracyMetres = 6f),
        )
        assertEquals(setOf("home", "gym"), insideAfterFix(setOf("work", "gym"), readings))
    }

    @Test
    fun `places not in the reading are left alone`() {
        assertEquals(setOf("home", "work"), insideAfterFix(setOf("work"), listOf(home(distance = 0f, accuracy = 6f))))
    }
}
