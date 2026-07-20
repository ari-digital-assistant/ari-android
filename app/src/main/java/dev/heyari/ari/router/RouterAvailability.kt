package dev.heyari.ari.router

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private val Context.routerAvailabilityDataStore by preferencesDataStore(name = "ari_router_availability")

/**
 * Whether a router model is published for a given locale.
 *
 * CI ships one model per locale, so a language Ari speaks is not
 * necessarily a language the router covers — and serving another locale's
 * model is never acceptable. Rather than hardcoding the supported set
 * (which would make every new language an Android release), this asks the
 * release endpoint and caches the answer.
 *
 * Verdicts expire after [TTL_MILLIS] in both directions: a 404 today must
 * not write the locale off forever once its model lands, and a 200 must not
 * outlive an artifact being pulled.
 */
@Singleton
class RouterAvailability @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun isAvailable(locale: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.routerAvailabilityDataStore.data.first()
        val cached = prefs[availableKey(locale)]
        val checkedAt = prefs[checkedAtKey(locale)]
        if (cached != null && checkedAt != null && isFresh(checkedAt, nowMillis)) return cached

        val probed = probe(locale)
        if (probed == null) {
            // No answer. Fall back to what we last knew, and stay optimistic
            // if we've never known: a wrong "no router" costs the user their
            // routing tier, a wrong "yes" costs one failed download attempt.
            return cached ?: true
        }
        context.routerAvailabilityDataStore.edit {
            it[availableKey(locale)] = probed
            it[checkedAtKey(locale)] = nowMillis
        }
        Log.i(TAG, "router availability for $locale: $probed")
        return probed
    }

    private suspend fun probe(locale: String): Boolean? = withContext(Dispatchers.IO) {
        val conn = try {
            (URL(RouterModel.manifestUrl(locale)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                connect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "router availability probe failed for $locale: ${e.message}")
            return@withContext null
        }
        try {
            verdictFor(conn.responseCode)
        } catch (e: Exception) {
            Log.w(TAG, "router availability probe failed for $locale: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "RouterAvailability"

        const val TTL_MILLIS = 24L * 60 * 60 * 1000

        /**
         * 200 means published, 404 means genuinely absent, anything else is
         * not an answer — a transient 5xx cached as "no router" would
         * disable routing for a whole day.
         */
        fun verdictFor(responseCode: Int): Boolean? = when (responseCode) {
            HttpURLConnection.HTTP_OK -> true
            HttpURLConnection.HTTP_NOT_FOUND -> false
            else -> null
        }

        /** A negative age means the clock jumped, so treat the cache as stale. */
        fun isFresh(checkedAtMillis: Long, nowMillis: Long): Boolean =
            nowMillis - checkedAtMillis in 0 until TTL_MILLIS

        private fun availableKey(locale: String) = booleanPreferencesKey("router_available_$locale")
        private fun checkedAtKey(locale: String) = longPreferencesKey("router_checked_at_$locale")
    }
}
