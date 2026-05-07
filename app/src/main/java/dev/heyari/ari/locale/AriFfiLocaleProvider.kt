package dev.heyari.ari.locale

import dev.heyari.ari.data.SettingsRepository
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import uniffi.ari_ffi.FfiLocaleProvider

/**
 * Android implementation of the engine's [FfiLocaleProvider] callback
 * trait. The frontend's [SettingsRepository] is the single source of
 * truth for the user's currently-active language; this class exposes
 * that to the engine through a synchronous, thread-safe getter.
 *
 * The engine invokes [currentLocale] from whatever thread Rust is
 * currently on (sometimes the app's main thread), so a per-call
 * `runBlocking { activeLocale.first() }` would be unsafe — DataStore
 * reads are non-blocking after the first access but the contract
 * doesn't guarantee that, and the caller has no patience for IO. We
 * keep an [AtomicReference] cache instead, updated by a coroutine
 * that collects the [SettingsRepository.activeLocale] flow for the
 * lifetime of the application.
 *
 * No capability required — every skill (and every internal engine
 * subsystem) can read the active locale.
 */
@Singleton
class AriFfiLocaleProvider @Inject constructor(
    settingsRepository: SettingsRepository,
) : FfiLocaleProvider {

    private val cached = AtomicReference(SupportedLocales.defaultFromSystem())

    init {
        // GlobalScope is the right scope for an app-lifetime singleton
        // that mirrors a long-lived DataStore flow into a synchronous
        // cache. The coroutine lives until the process dies; cancelling
        // it earlier would leave [currentLocale] returning stale values
        // for the rest of the app's runtime.
        @OptIn(DelicateCoroutinesApi::class)
        scope.launch {
            settingsRepository.activeLocale.collect { cached.set(it) }
        }
    }

    override fun currentLocale(): String = cached.get()

    private companion object {
        @OptIn(DelicateCoroutinesApi::class)
        private val scope: CoroutineScope =
            CoroutineScope(GlobalScope.coroutineContext + SupervisorJob() + Dispatchers.IO)
    }
}
