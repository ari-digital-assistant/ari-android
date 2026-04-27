package dev.heyari.ari.actions

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An envelope the engine produced asynchronously (outside the normal
 * `processInput` request/response cycle). Currently only phase-2 of a
 * Layer C assistant round-trip — the engine calls the assistant on a
 * background thread, then pushes the continuation envelope here. The
 * viewmodel subscribes to [AsyncEnvelopeChannel.flow] and appends
 * these as additional assistant messages.
 *
 * @property envelopeJson serialised `v:1` envelope. Frontend parses it
 *     via [PresentationEnvelope.parse] exactly like a synchronous
 *     `FfiResponse.Action`.
 * @property skillId emitting skill id when the engine knew one —
 *     null for engine-origin envelopes. Used by the action handler to
 *     resolve `asset:` references back to the skill's bundle.
 */
data class PushedEnvelope(
    val envelopeJson: String,
    val skillId: String?,
)

/**
 * Singleton channel between the native `FfiEnvelopeSink` implementation
 * (called from a Rust background thread after a Layer C assistant
 * round-trip) and whatever Compose-side observer wants to surface the
 * envelope.
 *
 * Emission is non-suspending ([MutableSharedFlow.tryEmit]) — the
 * background thread delivering from JNI can't afford to block on
 * backpressure. `DROP_OLDEST` keeps the buffer bounded without losing
 * the most recent pushes. `replay = 0` deliberately: any
 * ConversationViewModel re-attachment (navigation, config change) must
 * not see a previous envelope replayed and double-speak its contents.
 */
@Singleton
class AsyncEnvelopeChannel @Inject constructor() {
    private val _flow = MutableSharedFlow<PushedEnvelope>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<PushedEnvelope> = _flow

    fun emit(envelope: PushedEnvelope) {
        _flow.tryEmit(envelope)
    }
}
