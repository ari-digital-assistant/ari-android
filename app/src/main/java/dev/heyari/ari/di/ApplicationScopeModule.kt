package dev.heyari.ari.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Marker for the singleton, process-lifetime [CoroutineScope] used for
 * "must finish even if the caller dies" work — currently per-skill
 * settings persistence, which fires from a Composable's `onDispose`
 * just as the hosting NavBackStackEntry is being popped (and its
 * `viewModelScope` cancelled). Without this scope those writes get
 * silently dropped.
 *
 * Kept distinct from `viewModelScope` so it's obvious at the call site
 * when work intentionally outlives the screen that triggered it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        // SupervisorJob so a failure in one persistence write doesn't
        // cancel future ones. Dispatchers.IO is the right default for
        // DataStore + EncryptedSharedPreferences — both are blocking
        // I/O underneath their suspend wrappers.
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
