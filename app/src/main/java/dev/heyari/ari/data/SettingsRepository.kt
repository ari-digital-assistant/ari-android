package dev.heyari.ari.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.bugreport.FiledReportRecord
import dev.heyari.ari.bugreport.decodeReports
import dev.heyari.ari.bugreport.encodeReports
import dev.heyari.ari.listening.ListeningCondition
import dev.heyari.ari.listening.ListeningMode
import dev.heyari.ari.listening.ListeningPlace
import dev.heyari.ari.listening.ListeningSchedule
import dev.heyari.ari.listening.decodeConditions
import dev.heyari.ari.listening.decodePlaces
import dev.heyari.ari.listening.decodeSchedules
import dev.heyari.ari.listening.encodeConditions
import dev.heyari.ari.listening.encodePlaces
import dev.heyari.ari.listening.encodeSchedules
import dev.heyari.ari.locale.SupportedLocales
import dev.heyari.ari.stt.CloudTranscriber
import dev.heyari.ari.stt.SttMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val ASSISTANT_CONFIG_PREFIX = "assistant_config_"
private const val CONTRIBUTION_WATERMARK_PREFIX = "contrib_watermark_"

/** What [SettingsRepository.contributorId] mints, and the only thing the bucket accepts. */
private val CONTRIBUTOR_ID_FORMAT =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val Context.dataStore by preferencesDataStore(name = "ari_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * On-device or cloud transcription. Defaults to [SttMode.ON_DEVICE] so an
     * install that has never opened Settings — and every install that predates
     * the cloud option — keeps working offline with no endpoint and no key.
     */
    val sttMode: Flow<SttMode> = context.dataStore.data.map { prefs ->
        SttMode.fromSlug(prefs[KEY_STT_MODE])
    }.distinctUntilChanged()

    suspend fun setSttMode(mode: SttMode) {
        context.dataStore.edit { prefs -> prefs[KEY_STT_MODE] = mode.slug }
    }

    /**
     * Base URL for [SttMode.SELF_HOSTED] only. [SttMode.OPENAI] uses a fixed
     * endpoint that the user never sees — see [CloudTranscriber].
     */
    val cloudSttEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_STT_ENDPOINT] ?: CloudTranscriber.DEFAULT_SELF_HOSTED_ENDPOINT
    }.distinctUntilChanged()

    suspend fun setCloudSttEndpoint(url: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOUD_STT_ENDPOINT] = url.trim() }
    }

    /** Model name sent in the request. Servers disagree on what they call
     *  Whisper, so it has to be editable. */
    val cloudSttModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_STT_MODEL]?.takeIf { it.isNotBlank() } ?: CloudTranscriber.DEFAULT_SELF_HOSTED_MODEL
    }.distinctUntilChanged()

    suspend fun setCloudSttModel(model: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOUD_STT_MODEL] = model.trim() }
    }

    val activeSttModelId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_STT_MODEL]
    }.distinctUntilChanged()

    suspend fun setActiveSttModelId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_STT_MODEL)
            else prefs[KEY_ACTIVE_STT_MODEL] = id
        }
    }

    val activeWakeWordId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_WAKE_WORD]
    }.distinctUntilChanged()

    suspend fun setActiveWakeWordId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_WAKE_WORD] = id
        }
    }

    val wakeWordSensitivity: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD_SENSITIVITY]
    }.distinctUntilChanged()

    suspend fun setWakeWordSensitivity(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_WORD_SENSITIVITY] = name
        }
    }

    /**
     * The ladder measured on this phone, replacing the model's built-in one.
     * Null until somebody tunes. See [dev.heyari.ari.wakeword.TunedLadder].
     */
    val wakeLadder: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_LADDER]
    }.distinctUntilChanged()

    suspend fun setWakeLadder(formatted: String?) {
        context.dataStore.edit { prefs ->
            if (formatted == null) prefs.remove(KEY_WAKE_LADDER)
            else prefs[KEY_WAKE_LADDER] = formatted
        }
    }

    /**
     * Whether Ari may tighten its own sensitivity when it keeps waking for
     * nothing. Default off: it changes behaviour the user did not ask for on
     * the strength of something they did not report.
     */
    val tuneDuringNormalUse: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TUNE_DURING_NORMAL_USE] ?: false
    }.distinctUntilChanged()

    suspend fun setTuneDuringNormalUse(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_TUNE_DURING_NORMAL_USE] = enabled }
    }

    /**
     * When each recent false wake happened, comma-separated epoch millis.
     *
     * Timestamps rather than a count, because the question is a rate and a
     * counter cannot answer it — and because the list is the only record of
     * what a normal false-wake rate looks like on real phones, which nobody
     * has ever measured. Pruned to the monitor's window on every write.
     */
    val falseWakeLog: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_FALSE_WAKE_LOG]
    }.distinctUntilChanged()

    suspend fun setFalseWakeLog(csv: String) {
        context.dataStore.edit { prefs -> prefs[KEY_FALSE_WAKE_LOG] = csv }
    }

    /**
     * When Ari last made itself stricter on its own, epoch millis, or 0.
     *
     * Kept so it can come back and ask whether that was a mistake. A tightening
     * that stops somebody being heard produces no complaint, no log line and no
     * wake to notice the absence of — the only thing that catches it is asking.
     */
    val ladderTightenedAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LADDER_TIGHTENED_AT] ?: 0L
    }.distinctUntilChanged()

    suspend fun setLadderTightenedAt(epochMs: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_LADDER_TIGHTENED_AT] = epochMs }
    }

    /** The ladder in force before the last self-tightening, so it can be undone. */
    val ladderBeforeTightening: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LADDER_BEFORE_TIGHTENING]
    }.distinctUntilChanged()

    suspend fun setLadderBeforeTightening(formatted: String?) {
        context.dataStore.edit { prefs ->
            if (formatted == null) prefs.remove(KEY_LADDER_BEFORE_TIGHTENING)
            else prefs[KEY_LADDER_BEFORE_TIGHTENING] = formatted
        }
    }

    /** The selected LLM tier id, or "none" / null if disabled. */
    val activeLlmModelId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LLM_MODEL]
    }.distinctUntilChanged()

    suspend fun setActiveLlmModelId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_LLM_MODEL)
            else prefs[KEY_ACTIVE_LLM_MODEL] = id
        }
    }

    /** The active assistant skill ID, or null if none. */
    val activeAssistantId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_ASSISTANT]
    }.distinctUntilChanged()

    suspend fun setActiveAssistantId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_ASSISTANT)
            else prefs[KEY_ACTIVE_ASSISTANT] = id
        }
    }

    /**
     * Whether to start the wake word service on device boot. Default off —
     * auto-starting a microphone FGS is a privacy-visible behaviour we only
     * want happening when the user has explicitly said yes.
     */
    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_START_ON_BOOT] ?: false
    }.distinctUntilChanged()

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
        }
    }

    /**
     * Whether to keep the wake moment of an ACCEPTED turn — a confirmed true
     * positive. Default off, like every capture toggle: this writes microphone
     * audio to app-private storage and must never be on unless the user asked
     * for it.
     */
    val keepWakeAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_WAKE_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setKeepWakeAudio(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_WAKE_AUDIO] = enabled
        }
    }

    /**
     * Whether to keep audio that falsely triggered the wake word, for a future
     * model retrain. Default off — same reason as [keepWakeAudio].
     */
    val keepFalseTriggerAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_FALSE_TRIGGER_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setKeepFalseTriggerAudio(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_FALSE_TRIGGER_AUDIO] = enabled
        }
    }

    /**
     * Whether to keep a recording of every command the user speaks, alongside
     * the transcripts it produced, so mis-hearings can be diagnosed against the
     * audio. Default off, for the same reason as [keepFalseTriggerAudio] — and
     * more so, since this one records what the user meant to say to Ari.
     */
    val keepUtteranceAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_UTTERANCE_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setKeepUtteranceAudio(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_UTTERANCE_AUDIO] = enabled
        }
    }

    /**
     * The firehose: keep every wake-word firing and every spoken turn,
     * regardless of the three per-feature toggles above. Default off — this is
     * the most privacy-hostile switch in the app and exists so a whole
     * session's audio can be reconstructed when chasing a bug.
     */
    val keepEverythingAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_KEEP_EVERYTHING_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setKeepEverythingAudio(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_KEEP_EVERYTHING_AUDIO] = enabled
        }
    }

    /**
     * Whether accepted-wake recordings may be contributed to the training
     * corpus. Default off, and only meaningful while [keepWakeAudio] (or the
     * firehose) is on — a category that is not kept has nothing to send. The
     * UI enforces that pairing; [dev.heyari.ari.contrib.ContributionUploader]
     * enforces it again at sweep time, because a toggle left on from before a
     * keep switch went off must not resurrect old clips.
     */
    val shareWakeAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHARE_WAKE_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setShareWakeAudio(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHARE_WAKE_AUDIO] = enabled }
    }

    /** Whether false-trigger recordings may be contributed. See [shareWakeAudio]. */
    val shareFalseTriggerAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHARE_FALSE_TRIGGER_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setShareFalseTriggerAudio(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHARE_FALSE_TRIGGER_AUDIO] = enabled }
    }

    /** Whether command recordings may be contributed. See [shareWakeAudio]. */
    val shareUtteranceAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHARE_UTTERANCE_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setShareUtteranceAudio(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHARE_UTTERANCE_AUDIO] = enabled }
    }

    /**
     * The sharing counterpart of [keepEverythingAudio]: contribute every
     * category, regardless of the three toggles above. See [shareWakeAudio].
     */
    val shareEverythingAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHARE_EVERYTHING_AUDIO] ?: false
    }.distinctUntilChanged()

    suspend fun setShareEverythingAudio(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHARE_EVERYTHING_AUDIO] = enabled }
    }

    /**
     * True once anything has actually been uploaded, which is what decides
     * whether "Delete my shared data" is worth offering. Distinct from the
     * share toggles: those say what the user intends, this says what exists on
     * the server, and only the second one can be deleted.
     */
    val hasSharedRecordings: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HAS_SHARED_RECORDINGS] ?: false
    }.distinctUntilChanged()

    suspend fun setHasSharedRecordings(shared: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_HAS_SHARED_RECORDINGS] = shared }
    }

    /**
     * The contributor's anonymous id, minted on first use.
     *
     * Deliberately NOT [installId]: that one is attached to bug reports, and
     * reusing it would tie somebody's voice recordings to the issues they
     * filed. This id names a prefix in the contribution bucket and nothing
     * else — no device, no person, no account.
     *
     * It is also the only credential there is. Whoever holds it can delete
     * everything under that prefix, which is what makes deletion possible from
     * a phone that has been wiped: the user keeps the code, not us. That is
     * why it is a full random UUID rather than something short and memorable.
     */
    suspend fun contributorId(): String {
        context.dataStore.data.first()[KEY_CONTRIBUTOR_ID]?.let { return it }
        // Transactional, so two callers racing keep the first id — same
        // reasoning as installId().
        return context.dataStore.edit { prefs ->
            prefs[KEY_CONTRIBUTOR_ID] = prefs[KEY_CONTRIBUTOR_ID] ?: UUID.randomUUID().toString()
        }[KEY_CONTRIBUTOR_ID]!!
    }

    /** Null until something has been shared, so the UI can stay quiet until then. */
    val contributorIdIfMinted: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONTRIBUTOR_ID]
    }.distinctUntilChanged()

    /**
     * Adopt an id the user carried over from a previous install, so their
     * contributions land back under the prefix they can already delete.
     * Replaces whatever was there — the code IS the identity.
     *
     * False when [code] is not one. The server refuses anything that is not a
     * UUID too, but a typo caught here costs the user a toast rather than a
     * silent batch of uploads that never arrive.
     */
    suspend fun adoptContributorId(code: String): Boolean {
        val id = code.trim().lowercase(Locale.ROOT)
        if (!CONTRIBUTOR_ID_FORMAT.matches(id)) return false
        context.dataStore.edit { prefs -> prefs[KEY_CONTRIBUTOR_ID] = id }
        return true
    }

    /**
     * The newest clip stem already uploaded from [dirName], or null if none.
     *
     * A high-water mark rather than a per-file marker: clip stems are
     * fixed-width timestamp prefixes (see `clipStem`), so lexicographic order
     * is chronological order, and one string per directory survives the
     * eviction that trims the directory underneath it. A clock that jumps
     * backwards can strand a clip below the mark and it will never be sent —
     * acceptable for a corpus that is opportunistic by design.
     */
    fun contributionWatermark(dirName: String): Flow<String?> =
        context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(CONTRIBUTION_WATERMARK_PREFIX + dirName)]
        }.distinctUntilChanged()

    suspend fun setContributionWatermark(dirName: String, stem: String) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(CONTRIBUTION_WATERMARK_PREFIX + dirName)] = stem
        }
    }

    /**
     * Whether the user may interrupt Ari mid-sentence during a "Let's talk"
     * conversation. Default on — it's the natural feel of a conversation.
     * Effective barge-in additionally requires a hardware echo canceller
     * (see VoiceSession); when unavailable, talk mode stays turn-based.
     */
    val bargeInEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BARGE_IN_ENABLED] ?: true
    }.distinctUntilChanged()

    suspend fun setBargeInEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BARGE_IN_ENABLED] = enabled
        }
    }

    /**
     * Whether Ari keeps conversation memory: cross-turn context AND "Let's
     * talk" mode. Default on — the buffer is ephemeral (in-RAM, short TTL,
     * never persisted). When off the engine retains nothing and refuses
     * "let's talk" entry.
     */
    val conversationMemoryEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONVERSATION_MEMORY_ENABLED] ?: true
    }.distinctUntilChanged()

    suspend fun setConversationMemoryEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONVERSATION_MEMORY_ENABLED] = enabled
        }
    }

    /**
     * Durable personal facts the user explicitly asked Ari to remember. Stored
     * as a JSON array string. Empty when nothing has been remembered.
     */
    val rememberedFacts: Flow<List<String>> = context.dataStore.data.map { prefs ->
        decodeFacts(prefs[KEY_REMEMBERED_FACTS])
    }.distinctUntilChanged()

    suspend fun rememberedFactsOnce(): List<String> =
        decodeFacts(context.dataStore.data.first()[KEY_REMEMBERED_FACTS])

    suspend fun setRememberedFacts(facts: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REMEMBERED_FACTS] = encodeFacts(facts)
        }
    }

    private fun encodeFacts(facts: List<String>): String {
        val arr = org.json.JSONArray()
        facts.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeFacts(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    /**
     * Read/write per-assistant config values. Scoped by skill ID + key.
     * Used for non-secret config (model name, endpoint URL, etc.).
     */
    fun assistantConfigValue(skillId: String, key: String): Flow<String?> =
        context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("assistant_config_${skillId}_${key}")]
        }.distinctUntilChanged()

    suspend fun setAssistantConfigValue(skillId: String, key: String, value: String?) {
        val prefKey = stringPreferencesKey("assistant_config_${skillId}_${key}")
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(prefKey)
            else prefs[prefKey] = value
        }
    }

    /**
     * Snapshot of every `assistant_config_*` entry currently on disk,
     * decoded back into `(skillId, key, value)` triples. Used at app
     * startup to rehydrate the in-memory `SkillSettingsStore` so a
     * skill reading `ari::setting_get` sees the user's chosen
     * defaults without first having to visit the skill's settings
     * page.
     *
     * The DataStore key format `assistant_config_<skillId>_<fieldKey>`
     * has no unambiguous separator, so we rely on the convention that
     * skill IDs are reverse-DNS (dots, no underscores) and field keys
     * are snake_case (underscores, no dots). The first underscore after
     * the `assistant_config_` prefix marks the boundary. A skill author
     * who introduces an underscore into their reverse-DNS id would
     * break this; no enforcement exists today.
     */
    suspend fun allAssistantConfigEntries(): List<AssistantConfigEntry> {
        val prefs = context.dataStore.data.first()
        return prefs.asMap().entries.mapNotNull { (dsKey, rawValue) ->
            val name = dsKey.name
            if (!name.startsWith(ASSISTANT_CONFIG_PREFIX) || rawValue !is String) return@mapNotNull null
            val remainder = name.removePrefix(ASSISTANT_CONFIG_PREFIX)
            val boundary = remainder.indexOf('_')
            if (boundary <= 0 || boundary >= remainder.length - 1) return@mapNotNull null
            AssistantConfigEntry(
                skillId = remainder.substring(0, boundary),
                key = remainder.substring(boundary + 1),
                value = rawValue,
            )
        }
    }

    data class AssistantConfigEntry(val skillId: String, val key: String, val value: String)

    /**
     * A random id for this installation, minted on first use.
     *
     * Attached to bug reports so several reports from the same tester can be
     * told apart from several testers reporting once, and so the server can
     * rate-limit one device without knowing who it belongs to. It identifies
     * an install and nothing else: not the device, not the person, and it dies
     * with the app's data.
     */
    suspend fun installId(): String {
        context.dataStore.data.first()[KEY_INSTALL_ID]?.let { return it }
        // Two callers racing here would each generate one; `edit` is
        // transactional, so the second sees the first's value and keeps it.
        return context.dataStore.edit { prefs ->
            prefs[KEY_INSTALL_ID] = prefs[KEY_INSTALL_ID] ?: UUID.randomUUID().toString()
        }[KEY_INSTALL_ID]!!
    }

    /**
     * Where the tester has parked the bug-report button, as fractions of the
     * screen rather than pixels — a rotation or a different display would make
     * absolute coordinates point off-screen.
     *
     * Null until it is first dragged, which the button reads as "top right".
     */
    val bugReportFabPosition: Flow<Pair<Float, Float>?> = context.dataStore.data.map { prefs ->
        val x = prefs[KEY_FAB_X]
        val y = prefs[KEY_FAB_Y]
        if (x != null && y != null) x to y else null
    }.distinctUntilChanged()

    suspend fun setBugReportFabPosition(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAB_X] = x
            prefs[KEY_FAB_Y] = y
        }
    }

    /**
     * Bug reports filed from this phone, newest first.
     *
     * Local only, and deliberately so: it holds the delete tokens, which are
     * the one thing that can withdraw a report. Nothing on the server can
     * rebuild this list, which is exactly what the consent text warns about.
     */
    val filedReports: Flow<List<FiledReportRecord>> = context.dataStore.data.map { prefs ->
        decodeReports(prefs[KEY_FILED_REPORTS])
    }.distinctUntilChanged()

    suspend fun addFiledReport(report: FiledReportRecord) {
        context.dataStore.edit { prefs ->
            val existing = decodeReports(prefs[KEY_FILED_REPORTS])
                .filterNot { it.reportId == report.reportId }
            prefs[KEY_FILED_REPORTS] =
                encodeReports((listOf(report) + existing).take(FiledReportRecord.MAX_RECORDS))
        }
    }

    suspend fun removeFiledReport(reportId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILED_REPORTS] = encodeReports(
                decodeReports(prefs[KEY_FILED_REPORTS]).filterNot { it.reportId == reportId }
            )
        }
    }

    /** Whether the first-run onboarding wizard has been completed (or skipped). */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }.distinctUntilChanged()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    /** The user's chosen TTS voice name, or null for system default. */
    val activeTtsVoice: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_TTS_VOICE]
    }.distinctUntilChanged()

    suspend fun setActiveTtsVoice(name: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) prefs.remove(KEY_ACTIVE_TTS_VOICE)
            else prefs[KEY_ACTIVE_TTS_VOICE] = name
        }
    }

    /**
     * The user's currently-selected language, ISO 639-1 lowercase.
     * Defaults to the system language if Ari supports it, otherwise
     * `"en"`. The frontend is the single source of truth for locale;
     * the engine and skills read through here via the
     * `FfiLocaleProvider` host capability.
     *
     * Default is computed on every read rather than stored on first
     * launch, so a user who changes their phone's system language
     * before the onboarding wizard runs sees the right starting value.
     * Once they pick explicitly, [setActiveLocale] writes it through.
     */
    val activeLocale: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LOCALE] ?: SupportedLocales.defaultFromSystem()
    }.distinctUntilChanged()

    suspend fun setActiveLocale(code: String) {
        require(SupportedLocales.isSupported(code)) {
            "Unsupported locale: $code (supported: ${SupportedLocales.codes})"
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_LOCALE] = code
        }
    }

    /**
     * Set to `true` when the onboarding wizard finishes with the user
     * having picked the Cloud assistant option. Cleared once they
     * actually install (and activate) a cloud assistant skill — at
     * that point they have what they wanted, so the conversation-
     * screen "you still need to install one" hint should disappear.
     *
     * Lives here rather than in OnboardingViewModel because it has
     * to outlive the wizard's lifecycle — we still want to nag the
     * user days later if they exited the wizard without finishing
     * the install step.
     */
    val pendingCloudAssistantSetup: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PENDING_CLOUD_ASSISTANT_SETUP] ?: false
    }.distinctUntilChanged()

    suspend fun setPendingCloudAssistantSetup(pending: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PENDING_CLOUD_ASSISTANT_SETUP] = pending
        }
    }

    /**
     * Whether to route non-English transcription through the user's cloud
     * assistant instead of the on-device Whisper-turbo model. Off by
     * default; only meaningful when a cloud assistant is configured.
     *
     * Recorded here as a stable user preference; the actual cloud-STT
     * call path (when a cloud assistant supports STT) reads this flag
     * before deciding which transcriber to invoke.
     */
    // `cloud_stt_for_non_english` used to live here. It was never wired to a
    // call path — flipping it did nothing — and it is superseded by [sttMode],
    // which is a real choice in every locale. The stored key is left orphaned
    // rather than migrated: it never affected behaviour, so there is no state
    // worth carrying forward.

    /**
     * When Ari is allowed to open the microphone. Defaults to
     * [ListeningMode.ALWAYS] — the behaviour every install had before modes
     * existed, so upgrading can't silently make someone's assistant deaf.
     */
    val listeningMode: Flow<ListeningMode> = context.dataStore.data.map { prefs ->
        ListeningMode.fromSlug(prefs[KEY_LISTENING_MODE])
    }.distinctUntilChanged()

    suspend fun setListeningMode(mode: ListeningMode) {
        context.dataStore.edit { prefs -> prefs[KEY_LISTENING_MODE] = mode.slug }
    }

    /** The conditions ticked under [ListeningMode.CUSTOM]. ORed, never ANDed. */
    val listeningConditions: Flow<Set<ListeningCondition>> = context.dataStore.data.map { prefs ->
        decodeConditions(prefs[KEY_LISTENING_CONDITIONS])
    }.distinctUntilChanged()

    suspend fun setListeningConditions(conditions: Set<ListeningCondition>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LISTENING_CONDITIONS] = encodeConditions(conditions)
        }
    }

    /** Recurring listening windows, as a JSON array. Empty until the user adds one. */
    val listeningSchedules: Flow<List<ListeningSchedule>> = context.dataStore.data.map { prefs ->
        decodeSchedules(prefs[KEY_LISTENING_SCHEDULES])
    }.distinctUntilChanged()

    suspend fun listeningSchedulesOnce(): List<ListeningSchedule> =
        decodeSchedules(context.dataStore.data.first()[KEY_LISTENING_SCHEDULES])

    suspend fun setListeningSchedules(schedules: List<ListeningSchedule>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LISTENING_SCHEDULES] = encodeSchedules(schedules)
        }
    }

    /** Geofenced places to listen at, as a JSON array. */
    val listeningPlaces: Flow<List<ListeningPlace>> = context.dataStore.data.map { prefs ->
        decodePlaces(prefs[KEY_LISTENING_PLACES])
    }.distinctUntilChanged()

    suspend fun listeningPlacesOnce(): List<ListeningPlace> =
        decodePlaces(context.dataStore.data.first()[KEY_LISTENING_PLACES])

    suspend fun setListeningPlaces(places: List<ListeningPlace>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LISTENING_PLACES] = encodePlaces(places)
        }
    }

    /**
     * Set when the wizard finishes with Schedule or Places ticked but nothing
     * configured. Drives the conversation-screen reminder card, exactly like
     * [pendingCloudAssistantSetup] — and for the same reason: the nag has to
     * outlive the wizard that raised it.
     */
    val pendingListeningSetup: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PENDING_LISTENING_SETUP] ?: false
    }.distinctUntilChanged()

    suspend fun setPendingListeningSetup(pending: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_PENDING_LISTENING_SETUP] = pending }
    }

    companion object {
        private val KEY_STT_MODE = stringPreferencesKey("stt_mode")
        private val KEY_CLOUD_STT_ENDPOINT = stringPreferencesKey("cloud_stt_endpoint")
        private val KEY_CLOUD_STT_MODEL = stringPreferencesKey("cloud_stt_model")
        private val KEY_ACTIVE_STT_MODEL = stringPreferencesKey("active_stt_model")
        private val KEY_ACTIVE_WAKE_WORD = stringPreferencesKey("active_wake_word")
        private val KEY_WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")
        private val KEY_WAKE_LADDER = stringPreferencesKey("wake_ladder")
        private val KEY_TUNE_DURING_NORMAL_USE = booleanPreferencesKey("tune_during_normal_use")
        private val KEY_FALSE_WAKE_LOG = stringPreferencesKey("false_wake_log")
        private val KEY_LADDER_TIGHTENED_AT = longPreferencesKey("ladder_tightened_at")
        private val KEY_LADDER_BEFORE_TIGHTENING = stringPreferencesKey("ladder_before_tightening")
        private val KEY_ACTIVE_LLM_MODEL = stringPreferencesKey("active_llm_model")
        private val KEY_ACTIVE_ASSISTANT = stringPreferencesKey("active_assistant")
        private val KEY_ACTIVE_TTS_VOICE = stringPreferencesKey("active_tts_voice")
        private val KEY_ACTIVE_LOCALE = stringPreferencesKey("active_locale")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_KEEP_WAKE_AUDIO =
            booleanPreferencesKey("keep_wake_audio")
        private val KEY_KEEP_FALSE_TRIGGER_AUDIO =
            booleanPreferencesKey("keep_false_trigger_audio")
        private val KEY_KEEP_UTTERANCE_AUDIO =
            booleanPreferencesKey("keep_utterance_audio")
        private val KEY_KEEP_EVERYTHING_AUDIO =
            booleanPreferencesKey("keep_everything_audio")
        private val KEY_SHARE_WAKE_AUDIO =
            booleanPreferencesKey("share_wake_audio")
        private val KEY_SHARE_FALSE_TRIGGER_AUDIO =
            booleanPreferencesKey("share_false_trigger_audio")
        private val KEY_SHARE_UTTERANCE_AUDIO =
            booleanPreferencesKey("share_utterance_audio")
        private val KEY_SHARE_EVERYTHING_AUDIO =
            booleanPreferencesKey("share_everything_audio")
        private val KEY_HAS_SHARED_RECORDINGS =
            booleanPreferencesKey("has_shared_recordings")
        private val KEY_CONTRIBUTOR_ID = stringPreferencesKey("contributor_id")
        private val KEY_BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
        private val KEY_CONVERSATION_MEMORY_ENABLED =
            booleanPreferencesKey("conversation_memory_enabled")
        private val KEY_REMEMBERED_FACTS = stringPreferencesKey("remembered_facts")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_INSTALL_ID = stringPreferencesKey("install_id")
        private val KEY_FILED_REPORTS = stringPreferencesKey("filed_bug_reports")
        private val KEY_FAB_X = floatPreferencesKey("bug_report_fab_x")
        private val KEY_FAB_Y = floatPreferencesKey("bug_report_fab_y")
        private val KEY_PENDING_CLOUD_ASSISTANT_SETUP = booleanPreferencesKey("pending_cloud_assistant_setup")
        private val KEY_LISTENING_MODE = stringPreferencesKey("listening_mode")
        private val KEY_LISTENING_CONDITIONS = stringPreferencesKey("listening_conditions")
        private val KEY_LISTENING_SCHEDULES = stringPreferencesKey("listening_schedules")
        private val KEY_LISTENING_PLACES = stringPreferencesKey("listening_places")
        private val KEY_PENDING_LISTENING_SETUP = booleanPreferencesKey("pending_listening_setup")
    }
}
