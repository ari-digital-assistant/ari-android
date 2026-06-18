package dev.heyari.ari.models

import dev.heyari.ari.data.AutoUpdatePreferences
import dev.heyari.ari.llm.LlmDownloadManager
import dev.heyari.ari.llm.LlmDownloadState
import dev.heyari.ari.router.RouterDownloadManager
import dev.heyari.ari.router.RouterDownloadState
import dev.heyari.ari.stt.ModelDownloadManager
import dev.heyari.ari.stt.ModelDownloadState
import dev.heyari.ari.stt.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.heyari.ari.di.EngineHolder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streamed events produced by [ModelUpdateApplier.apply]. Subscribers
 * (the Settings panel, the in-app banner) update their own UI state
 * from these — the applier itself doesn't know about ViewModels.
 */
sealed interface ApplyEvent {
    data class Started(val displayName: String) : ApplyEvent
    data class Progress(val bytesSoFar: Long, val totalBytes: Long) : ApplyEvent
    data class Completed(val displayName: String, val version: String) : ApplyEvent
    data class Failed(val displayName: String, val reason: String) : ApplyEvent
}

/**
 * Owns the `unload → download → reload` sequence for one on-device
 * model. Lifted out of [dev.heyari.ari.ui.settings.AutoUpdateViewModel]
 * so the in-app banner can drive in-place updates from the conversation
 * screen without that VM needing to depend on this VM's state.
 *
 * Each call returns a cold [Flow] of [ApplyEvent]s; collect once. The
 * flow terminates after [ApplyEvent.Completed] or [ApplyEvent.Failed].
 */
@Singleton
class ModelUpdateApplier @Inject constructor(
    private val engineHolder: EngineHolder,
    private val routerDownloadManager: RouterDownloadManager,
    private val llmDownloadManager: LlmDownloadManager,
    private val sttDownloadManager: ModelDownloadManager,
    private val speechRecognizer: SpeechRecognizer,
    private val autoUpdatePreferences: AutoUpdatePreferences,
) {
    fun apply(update: ModelUpdate): Flow<ApplyEvent> = channelFlow {
        send(ApplyEvent.Started(update.target.displayName))
        when (val target = update.target) {
            is ModelTarget.Router -> applyRouter(update)
            is ModelTarget.Llm -> applyLlm(update, target)
            is ModelTarget.Stt -> applyStt(update, target)
        }
    }

    private suspend fun ProducerScope<ApplyEvent>.applyRouter(update: ModelUpdate) {
        val engine = engineHolder.engine()
        // 1. Release the engine's mmap on the old GGUF before overwriting.
        withContext(Dispatchers.IO) { engine.unloadRouterModel() }

        // 2. Stream byte-level progress concurrently with the suspending
        //    download call. The progress collector is cancelled in the
        //    finally block so we don't leak past the download.
        val progressJob = launch {
            routerDownloadManager.state.collect { state ->
                if (state is RouterDownloadState.Downloading) {
                    trySendBlocking(ApplyEvent.Progress(state.bytesSoFar, state.totalBytes))
                }
            }
        }
        try {
            routerDownloadManager.downloadWithManifest(update.manifest)
        } finally {
            progressJob.cancel()
        }

        when (val finalState = routerDownloadManager.state.value) {
            is RouterDownloadState.Completed -> {
                val ok = withContext(Dispatchers.IO) {
                    engine.loadRouterModel(routerDownloadManager.modelFile().absolutePath)
                }
                if (!ok) {
                    send(ApplyEvent.Failed(update.target.displayName, "engine refused new model"))
                    return
                }
                autoUpdatePreferences.setSkippedVersion(update.target.key, null)
                send(ApplyEvent.Completed(update.target.displayName, update.availableVersion))
            }
            is RouterDownloadState.Failed -> {
                // Best-effort restore: the existing on-disk file may be
                // intact (rename never happened). Re-loading saves routing
                // for the rest of the session.
                if (routerDownloadManager.isDownloaded()) {
                    withContext(Dispatchers.IO) {
                        engine.loadRouterModel(routerDownloadManager.modelFile().absolutePath)
                    }
                }
                send(ApplyEvent.Failed(update.target.displayName, finalState.error))
            }
            else -> send(ApplyEvent.Failed(update.target.displayName, "download did not complete"))
        }
    }

    private suspend fun ProducerScope<ApplyEvent>.applyLlm(
        update: ModelUpdate,
        target: ModelTarget.Llm,
    ) {
        val model = target.model
        val engine = engineHolder.engine()
        withContext(Dispatchers.IO) { engine.unloadLlmModel() }

        val progressJob = launch {
            llmDownloadManager.state.collect { state ->
                if (state is LlmDownloadState.Downloading) {
                    trySendBlocking(ApplyEvent.Progress(state.bytesSoFar, state.totalBytes))
                }
            }
        }
        val finalState = try {
            llmDownloadManager.downloadAndAwait(model)
        } finally {
            progressJob.cancel()
        }

        when (finalState) {
            is LlmDownloadState.Completed -> {
                val ok = withContext(Dispatchers.IO) {
                    engine.loadLlmModel(llmDownloadManager.modelFile(model).absolutePath)
                }
                if (!ok) {
                    send(ApplyEvent.Failed(target.displayName, "engine refused new model"))
                    return
                }
                autoUpdatePreferences.setSkippedVersion(target.key, null)
                send(ApplyEvent.Completed(target.displayName, update.availableVersion))
            }
            is LlmDownloadState.Failed -> {
                if (llmDownloadManager.isDownloaded(model)) {
                    withContext(Dispatchers.IO) {
                        engine.loadLlmModel(llmDownloadManager.modelFile(model).absolutePath)
                    }
                }
                send(ApplyEvent.Failed(target.displayName, finalState.error))
            }
            else -> send(ApplyEvent.Failed(target.displayName, "download did not complete"))
        }
    }

    private suspend fun ProducerScope<ApplyEvent>.applyStt(
        update: ModelUpdate,
        target: ModelTarget.Stt,
    ) {
        val model = target.model
        withContext(Dispatchers.IO) { speechRecognizer.unload() }

        val progressJob = launch {
            sttDownloadManager.state.collect { state ->
                if (state is ModelDownloadState.Downloading) {
                    trySendBlocking(ApplyEvent.Progress(state.bytesSoFar, state.totalBytes))
                }
            }
        }
        val finalState = try {
            sttDownloadManager.downloadAndAwait(model)
        } finally {
            progressJob.cancel()
        }

        when (finalState) {
            is ModelDownloadState.Completed -> {
                val reloadResult = withContext(Dispatchers.IO) {
                    runCatching {
                        speechRecognizer.loadModel(model, sttDownloadManager.modelDir(model))
                    }
                }
                if (reloadResult.isFailure) {
                    send(
                        ApplyEvent.Failed(
                            target.displayName,
                            reloadResult.exceptionOrNull()?.message ?: "recogniser refused new model",
                        ),
                    )
                    return
                }
                autoUpdatePreferences.setSkippedVersion(target.key, null)
                send(ApplyEvent.Completed(target.displayName, update.availableVersion))
            }
            is ModelDownloadState.Failed -> {
                if (sttDownloadManager.isDownloaded(model)) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            speechRecognizer.loadModel(model, sttDownloadManager.modelDir(model))
                        }
                    }
                }
                send(ApplyEvent.Failed(target.displayName, finalState.error))
            }
            else -> send(ApplyEvent.Failed(target.displayName, "download did not complete"))
        }
    }
}
