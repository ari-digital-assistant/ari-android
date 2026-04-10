package dev.heyari.ari

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.heyari.ari.skills.SkillUpdateWorker
import uniffi.ari_ffi.AriEngine
import javax.inject.Inject

@HiltAndroidApp
class AriApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var engine: AriEngine

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Idempotent — KEEP policy means reinstalls don't reset the schedule.
        SkillUpdateWorker.schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.i("AriApplication", "Memory pressure (level=$level), unloading LLM")
            engine.unloadLlmModel()
        }
    }
}
