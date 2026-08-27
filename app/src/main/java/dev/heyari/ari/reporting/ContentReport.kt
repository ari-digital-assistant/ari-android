package dev.heyari.ari.reporting

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.heyari.ari.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What is being reported. The email labels the two differently. */
enum class ReportKind(val wireName: String) {
    RESPONSE("response"),
    SKILL("skill"),
}

/** Why the user is reporting something. Sent verbatim; the server rejects anything else. */
enum class ReportCategory(val wireName: String) {
    OFFENSIVE("offensive"),
    HARMFUL("harmful"),
    WRONG("wrong"),
    OTHER("other"),
}

/**
 * A report the user has chosen to send, exactly as the confirmation dialog
 * showed it to them.
 *
 * [prompt] is null when the user unticked "include what I said" — the offending
 * response alone is still worth having, and their own words are theirs to
 * withhold. [skillId] is null when the engine could not attribute the turn to a
 * skill, which is every plain-text answer: `FfiResponse.Text` carries no id, so
 * a guess here would be a fabrication in someone's inbox.
 */
data class ContentReport(
    val kind: ReportKind = ReportKind.RESPONSE,
    val category: ReportCategory,
    val text: String,
    val prompt: String? = null,
    val note: String? = null,
    val skillId: String? = null,
)

/**
 * Hands a report to WorkManager and returns.
 *
 * Deliberately fire-and-forget from the caller's point of view: the dialog says
 * "sent" the moment this returns, which is honest because WorkManager will keep
 * trying across a dead network and a process death. Blocking the user on an
 * HTTP round trip to tell them something they cannot act on would be worse.
 */
@Singleton
class ReportSender @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun send(report: ContentReport) {
        val data = Data.Builder()
            .putString(KEY_KIND, report.kind.wireName)
            .putString(KEY_CATEGORY, report.category.wireName)
            .putString(KEY_TEXT, report.text)
            .putString(KEY_PROMPT, report.prompt)
            .putString(KEY_NOTE, report.note)
            .putString(KEY_SKILL_ID, report.skillId)
            .putString(KEY_APP_VERSION, BuildConfig.VERSION_NAME)
            .build()

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ReportWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_CATEGORY = "category"
        const val KEY_TEXT = "text"
        const val KEY_PROMPT = "prompt"
        const val KEY_NOTE = "note"
        const val KEY_SKILL_ID = "skillId"
        const val KEY_APP_VERSION = "appVersion"
    }
}
