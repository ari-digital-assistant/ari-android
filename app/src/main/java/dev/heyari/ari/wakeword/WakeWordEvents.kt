package dev.heyari.ari.wakeword

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide bus for wake word detection events. Lives at the singleton scope so any
 * ViewModel (across navigation destinations) can react.
 */
@Singleton
class WakeWordEvents @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun onWakeWordDetected() {
        _events.tryEmit(Unit)
    }
}
