package dev.heyari.ari.messaging

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.handsFreeNudgeDataStore by preferencesDataStore(name = "ari_hands_free_nudge")

/**
 * Rations the "make me your assistant and I'll send that by voice" offer to
 * once every [THROTTLE_MILLIS].
 *
 * The answer is needed on [dev.heyari.ari.actions.ActionHandler]'s synchronous
 * path, so it's held in memory; it's persisted as well so that killing Ari
 * between two messages doesn't reset the clock and tell the user the same thing
 * twice. A read before hydration finishes reports the offer as due, which costs
 * at most one extra mention per cold start and never silences a legitimate one.
 */
@Singleton
class HandsFreeNudge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile
    private var lastShownAt: Long? = null

    init {
        scope.launch {
            val stored = context.handsFreeNudgeDataStore.data.first()[KEY_LAST_SHOWN_AT] ?: 0L
            // An offer made before hydration landed has already recorded a
            // newer timestamp — never walk it backwards.
            if (lastShownAt == null) lastShownAt = stored
        }
    }

    fun isDue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        Companion.isDue(lastShownAt, nowMillis)

    fun markShown(nowMillis: Long = System.currentTimeMillis()) {
        lastShownAt = nowMillis
        scope.launch {
            context.handsFreeNudgeDataStore.edit { it[KEY_LAST_SHOWN_AT] = nowMillis }
        }
    }

    companion object {
        const val THROTTLE_MILLIS = 6L * 60 * 60 * 1000

        /**
         * Kept out of the class so the interval can be tested without a
         * [Context], mirroring [dev.heyari.ari.actions.MessageLauncher.plan].
         * A null [lastShownAt] means "never offered, and not yet read back
         * from disk" — both of which are due.
         */
        internal fun isDue(lastShownAt: Long?, nowMillis: Long): Boolean =
            nowMillis - (lastShownAt ?: 0L) >= THROTTLE_MILLIS

        private val KEY_LAST_SHOWN_AT = longPreferencesKey("last_shown_at")
    }
}
