package dev.heyari.ari.listening

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A place Ari should listen at, expressed as a circle. Backed by a geofence, so
 * the radius floor is the geofencing API's own: below ~100 m the network
 * location fix isn't accurate enough to tell inside from outside, and you get a
 * geofence that flaps.
 */
data class ListeningPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Float,
) {
    companion object {
        const val MIN_RADIUS_METRES = 100f
        const val MAX_RADIUS_METRES = 2_000f
        const val DEFAULT_RADIUS_METRES = 150f

        /** Play Services caps a single app at 100 geofences per device user. */
        const val MAX_PLACES = 100
    }
}

internal fun encodePlaces(places: List<ListeningPlace>): String {
    val arr = JSONArray()
    places.forEach { place ->
        arr.put(
            JSONObject().apply {
                put("id", place.id)
                put("name", place.name)
                put("lat", place.latitude)
                put("lon", place.longitude)
                put("radius", place.radiusMetres.toDouble())
            }
        )
    }
    return arr.toString()
}

internal fun decodePlaces(raw: String?): List<ListeningPlace> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ListeningPlace(
                id = id,
                name = obj.optString("name"),
                latitude = obj.optDouble("lat"),
                longitude = obj.optDouble("lon"),
                radiusMetres = obj.optDouble("radius", ListeningPlace.DEFAULT_RADIUS_METRES.toDouble())
                    .toFloat()
                    .coerceIn(ListeningPlace.MIN_RADIUS_METRES, ListeningPlace.MAX_RADIUS_METRES),
            ).takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        }
    } catch (e: JSONException) {
        Log.w(TAG, "Corrupt place store — dropping all places", e)
        emptyList()
    }
}

private const val TAG = "ListeningPlace"
