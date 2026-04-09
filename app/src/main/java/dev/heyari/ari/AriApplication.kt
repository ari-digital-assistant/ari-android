package dev.heyari.ari

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.heyari.ari.skills.SkillUpdateWorker
import javax.inject.Inject

@HiltAndroidApp
class AriApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Idempotent — KEEP policy means reinstalls don't reset the schedule.
        SkillUpdateWorker.schedule(this)
    }
}
