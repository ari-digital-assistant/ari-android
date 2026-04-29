package dev.heyari.ari.updates

import dev.heyari.ari.models.ModelUpdate
import dev.heyari.ari.models.ModelUpdateNotifier
import dev.heyari.ari.skills.SkillUpdateNotifier
import dev.heyari.ari.updates.UpdatesPreferences.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import uniffi.ari_ffi.FfiSkillUpdate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight, persistable view of one pending update — enough for the
 * in-app banner to render without holding network-fetched manifests.
 *
 * The full [ModelUpdate] / [FfiSkillUpdate] objects are recomputed by the
 * banner's "Update All" path (a fresh `checkForUpdates()` call) so we never
 * apply stale-from-disk data.
 */
data class PendingUpdateSummary(
    val id: String,
    val displayName: String,
    val installedVersion: String,
    val availableVersion: String,
)

/**
 * Owns the persisted pending-update state for the in-app banner and
 * decides whether the workers should fire a system notification.
 *
 * The "system notification" gate works in two halves:
 *   1. **Inactive-user check** — only fire if `now - lastLaunchedAt >=
 *      INACTIVE_THRESHOLD`. Active users see the banner instead.
 *   2. **Once-per-fingerprint check** — even when the user is stale, we
 *      only fire once per pending-update set. A new update set (different
 *      fingerprint) re-arms the notifier.
 *
 * Workers call [recordCheck] after every poll. The banner reads
 * [modelUpdates] / [skillUpdates] reactively.
 */
@Singleton
class UpdatesRepository @Inject constructor(
    private val prefs: UpdatesPreferences,
    private val modelNotifier: ModelUpdateNotifier,
    private val skillNotifier: SkillUpdateNotifier,
) {
    /**
     * Reactive list of pending model updates as last seen by the worker.
     * Empty when nothing's pending or the user has dismissed the banner.
     */
    val modelUpdates: Flow<List<PendingUpdateSummary>> =
        combine(prefs.pendingPayload(Category.MODEL), prefs.seenFingerprint(Category.MODEL)) { payload, seen ->
            val items = decode(payload)
            if (items.isEmpty()) emptyList() else if (fingerprint(items) == seen) emptyList() else items
        }

    val skillUpdates: Flow<List<PendingUpdateSummary>> =
        combine(prefs.pendingPayload(Category.SKILL), prefs.seenFingerprint(Category.SKILL)) { payload, seen ->
            val items = decode(payload)
            if (items.isEmpty()) emptyList() else if (fingerprint(items) == seen) emptyList() else items
        }

    /**
     * Worker-side hook. Persists the new pending list and decides whether
     * to fire / cancel the system notification per the inactive-user gate.
     *
     * Called even when [pending] is empty so we cancel a stale system
     * notification (e.g. user installed an update via Settings).
     *
     * @param postSystemNotification When true (default, periodic worker
     *   path), the inactive-user gate decides whether to fire a system
     *   notification. When false (in-app opportunistic check), persist
     *   pending state but never fire a notif — the user is already in
     *   the app and the banner will surface it.
     */
    suspend fun recordCheck(
        category: Category,
        pending: List<PendingUpdateSummary>,
        postSystemNotification: Boolean = true,
    ) {
        val payload = encode(pending)
        prefs.setPendingPayload(category, payload.takeIf { it.isNotEmpty() })

        if (pending.isEmpty()) {
            prefs.setNotifiedFingerprint(category, null)
            cancelNotification(category)
            return
        }

        if (!postSystemNotification) {
            // In-app caller: pending list is now persisted (banner will
            // pick it up via its flow), and we cancel any stale system
            // notification since the user is currently looking at the app.
            cancelNotification(category)
            return
        }

        val fp = fingerprint(pending)
        val notified = prefs.notifiedFingerprint(category).first()
        if (notified == fp) {
            // Same set we've already nudged about — skip.
            return
        }

        val lastLaunched = prefs.lastLaunchedAt.first()
        val daysSinceLaunch = lastLaunched?.let {
            Duration.between(it, Instant.now()).toDays()
        }
        // null lastLaunched means the user has never opened the app since
        // this feature shipped — treat that as inactive too, so the worker
        // still pings them. Conservative: prefer a notification over silence.
        val inactive = lastLaunched == null || (daysSinceLaunch ?: 0L) >= INACTIVE_THRESHOLD_DAYS
        if (!inactive) {
            // Active user — banner will surface the update; don't pile on.
            cancelNotification(category)
            return
        }

        postNotification(category, pending)
        prefs.setNotifiedFingerprint(category, fp)
    }

    /**
     * Banner side: mark the current pending set as acknowledged so the
     * banner stops showing. Called when the user dismisses (X), taps
     * Details, or completes an Update All.
     */
    suspend fun markSeen(category: Category) {
        val payload = prefs.pendingPayload(category).first()
        val items = decode(payload)
        if (items.isEmpty()) return
        prefs.setSeenFingerprint(category, fingerprint(items))
    }

    /**
     * Called from MainActivity on launch. Resets `lastLaunchedAt` and
     * cancels any in-flight system notifications, since the user is now
     * looking at the app.
     */
    suspend fun recordLaunch() {
        prefs.setLastLaunchedAt(Instant.now())
        modelNotifier.cancel()
        skillNotifier.showOrUpdate(0)
    }

    private fun postNotification(category: Category, pending: List<PendingUpdateSummary>) {
        when (category) {
            Category.MODEL -> modelNotifier.showOrUpdate(pending.map { it.displayName })
            Category.SKILL -> skillNotifier.showOrUpdate(pending.size)
        }
    }

    private fun cancelNotification(category: Category) {
        when (category) {
            Category.MODEL -> modelNotifier.cancel()
            Category.SKILL -> skillNotifier.showOrUpdate(0)
        }
    }

    companion object {
        const val INACTIVE_THRESHOLD_DAYS = 3L

        fun summariesFromModelUpdates(updates: List<ModelUpdate>): List<PendingUpdateSummary> =
            updates.map {
                PendingUpdateSummary(
                    id = it.target.key,
                    displayName = it.target.displayName,
                    installedVersion = it.installedVersion,
                    availableVersion = it.availableVersion,
                )
            }

        fun summariesFromSkillUpdates(updates: List<FfiSkillUpdate>): List<PendingUpdateSummary> =
            updates.map {
                PendingUpdateSummary(
                    id = it.id,
                    displayName = it.name.ifBlank { it.id },
                    installedVersion = it.installedVersion,
                    availableVersion = it.availableVersion,
                )
            }

        // Control chars — Unicode SOH () and STX (). Neither
        // appears in skill IDs, model display names, or semver strings, so
        // the encoder doesn't need to escape and the decoder splits blindly.
        private const val FIELD_SEP = ""
        private const val ROW_SEP = ""

        internal fun encode(items: List<PendingUpdateSummary>): String =
            items.joinToString(ROW_SEP) {
                listOf(it.id, it.displayName, it.installedVersion, it.availableVersion)
                    .joinToString(FIELD_SEP)
            }

        internal fun decode(payload: String?): List<PendingUpdateSummary> {
            if (payload.isNullOrEmpty()) return emptyList()
            return payload.split(ROW_SEP).mapNotNull { row ->
                val parts = row.split(FIELD_SEP)
                if (parts.size != 4) null else PendingUpdateSummary(
                    id = parts[0],
                    displayName = parts[1],
                    installedVersion = parts[2],
                    availableVersion = parts[3],
                )
            }
        }

        internal fun fingerprint(items: List<PendingUpdateSummary>): String {
            if (items.isEmpty()) return ""
            val canonical = items
                .map { "${it.id}@${it.availableVersion}" }
                .sorted()
                .joinToString(",")
            val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
