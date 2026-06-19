package dev.heyari.ari.stt

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ModelDownloadManager.resolveState], the pure mapping
 * from a WorkManager work state (+ the optimistic "requested model id"
 * latch) to a [ModelDownloadState].
 *
 * The latch is what fixes the onboarding double-tap bug: the moment the
 * user taps Download we know the model id, so the UI must resolve to
 * Downloading(thatId) immediately — even before WorkManager has produced
 * any WorkInfo, and through the early ENQUEUED/RUNNING window where the
 * worker hasn't published its own model id into progress yet.
 */
class ModelDownloadStateTest {

    private fun resolve(
        workState: WorkInfo.State?,
        progressModelId: String = "",
        bytesSoFar: Long = 0L,
        totalBytes: Long = 0L,
        outputModelId: String = "",
        error: String? = null,
        requestedId: String? = null,
    ) = ModelDownloadManager.resolveState(
        workState, progressModelId, bytesSoFar, totalBytes, outputModelId, error, requestedId,
    )

    @Test
    fun noWorkInfoButRequested_isDownloadingThatModel() {
        // The crucial case: download() just latched the id, WorkManager
        // hasn't emitted any WorkInfo yet. The row must already show
        // progress for that model so its button can't be tapped again.
        assertEquals(
            ModelDownloadState.Downloading("hey-ari-en", 0L, 0L, ""),
            resolve(workState = null, requestedId = "hey-ari-en"),
        )
    }

    @Test
    fun noWorkInfoAndNothingRequested_isIdle() {
        assertEquals(ModelDownloadState.Idle, resolve(workState = null, requestedId = null))
    }

    @Test
    fun enqueuedWithRequest_carriesRequestedModelId() {
        // Early window: worker enqueued but no progress yet. Without the
        // latch this produced Downloading("") which matched no row, so
        // the download button stayed visible (the bug).
        assertEquals(
            ModelDownloadState.Downloading("whisper-turbo", 0L, 0L, ""),
            resolve(WorkInfo.State.ENQUEUED, requestedId = "whisper-turbo"),
        )
    }

    @Test
    fun runningWithoutProgressId_fallsBackToRequestedId() {
        assertEquals(
            ModelDownloadState.Downloading("whisper-turbo", 1024L, 4096L, ""),
            resolve(
                WorkInfo.State.RUNNING,
                progressModelId = "",
                bytesSoFar = 1024L,
                totalBytes = 4096L,
                requestedId = "whisper-turbo",
            ),
        )
    }

    @Test
    fun runningWithProgressId_prefersProgressId() {
        // Once the worker publishes its own model id, that wins over the
        // optimistic latch (they agree in practice, but progress is truth).
        assertEquals(
            ModelDownloadState.Downloading("kroko-en", 2048L, 4096L, ""),
            resolve(
                WorkInfo.State.RUNNING,
                progressModelId = "kroko-en",
                bytesSoFar = 2048L,
                totalBytes = 4096L,
                requestedId = "stale-id",
            ),
        )
    }

    @Test
    fun succeeded_isCompletedWithOutputId() {
        assertEquals(
            ModelDownloadState.Completed("kroko-en"),
            resolve(WorkInfo.State.SUCCEEDED, outputModelId = "kroko-en", requestedId = "kroko-en"),
        )
    }

    @Test
    fun failed_isFailedWithError() {
        assertEquals(
            ModelDownloadState.Failed("kroko-en", "network down"),
            resolve(WorkInfo.State.FAILED, outputModelId = "kroko-en", error = "network down"),
        )
    }

    @Test
    fun failedWithoutError_usesFallbackMessage() {
        assertEquals(
            ModelDownloadState.Failed("kroko-en", "Unknown error"),
            resolve(WorkInfo.State.FAILED, outputModelId = "kroko-en", error = null),
        )
    }

    @Test
    fun cancelled_isIdle() {
        assertEquals(
            ModelDownloadState.Idle,
            resolve(WorkInfo.State.CANCELLED, requestedId = "kroko-en"),
        )
    }
}
