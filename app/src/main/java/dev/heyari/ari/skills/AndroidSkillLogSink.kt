package dev.heyari.ari.skills

import android.util.Log
import uniffi.ari_ffi.FfiLogLevel
import uniffi.ari_ffi.FfiLogSink

/**
 * Forwards WASM-skill log lines (emitted via `ari::log(...)` in the skill
 * SDK) to `android.util.Log`. Without this, skill log output is silently
 * discarded, which makes debugging a sideloaded skill a blind affair.
 *
 * Tag is a fixed short string so `adb logcat -s AriSkill` catches every
 * skill's output in one go. The skill id is prepended to the message so
 * you can grep for a specific skill from the combined stream:
 *
 *     adb logcat -s AriSkill              # everything
 *     adb logcat -s AriSkill | grep reminder
 */
class AndroidSkillLogSink : FfiLogSink {
    override fun log(skillId: String, level: FfiLogLevel, message: String) {
        val line = "[$skillId] $message"
        when (level) {
            FfiLogLevel.TRACE -> Log.v(TAG, line)
            FfiLogLevel.DEBUG -> Log.d(TAG, line)
            FfiLogLevel.INFO -> Log.i(TAG, line)
            FfiLogLevel.WARN -> Log.w(TAG, line)
            FfiLogLevel.ERROR -> Log.e(TAG, line)
        }
    }

    private companion object {
        const val TAG = "AriSkill"
    }
}
