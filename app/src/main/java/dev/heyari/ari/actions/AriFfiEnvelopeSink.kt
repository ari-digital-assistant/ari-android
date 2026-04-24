package dev.heyari.ari.actions

import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ari_ffi.FfiEnvelopeSink

/**
 * UniFFI-exposed sink the engine invokes from a Rust background thread
 * when it has produced an asynchronous envelope (today only phase-2 of
 * a Layer C assistant round-trip). We push onto the
 * [AsyncEnvelopeChannel] which the conversation viewmodel observes on
 * the UI dispatcher — the JNI thread itself does nothing blocking and
 * returns straight away.
 *
 * No marshalling to the main thread happens here; the `MutableSharedFlow`
 * backing the channel handles cross-thread delivery naturally, and the
 * viewmodel's `collect` block hops to the right dispatcher.
 */
@Singleton
class AriFfiEnvelopeSink @Inject constructor(
    private val channel: AsyncEnvelopeChannel,
) : FfiEnvelopeSink {

    override fun push(envelopeJson: String, skillId: String?) {
        channel.emit(PushedEnvelope(envelopeJson, skillId))
    }
}
