package dev.heyari.ari.llm

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LlmDownloadManager.resolveState], the pure mapping from
 * a WorkManager work state (+ the optimistic "requested model id" latch)
 * to a [LlmDownloadState].
 *
 * The latch fixes the settings-screen double-tap: the moment the user taps
 * Download we know the model id, so the row must resolve to
 * Downloading(thatId) immediately — even before WorkManager has produced
 * any WorkInfo, and through the early ENQUEUED/RUNNING window where the
 * worker hasn't published its own model id into progress yet.
 */
class LlmDownloadStateTest {

    private fun resolve(
        workState: WorkInfo.State?,
        progressModelId: String = "",
        bytesSoFar: Long = 0L,
        totalBytes: Long = 0L,
        outputModelId: String = "",
        error: String? = null,
        requestedId: String? = null,
    ) = LlmDownloadManager.resolveState(
        workState, progressModelId, bytesSoFar, totalBytes, outputModelId, error, requestedId,
    )

    @Test
    fun noWorkInfoButRequested_isDownloadingThatModel() {
        assertEquals(
            LlmDownloadState.Downloading("gemma-small", 0L, 0L),
            resolve(workState = null, requestedId = "gemma-small"),
        )
    }

    @Test
    fun noWorkInfoAndNothingRequested_isIdle() {
        assertEquals(LlmDownloadState.Idle, resolve(workState = null, requestedId = null))
    }

    @Test
    fun enqueuedWithRequest_carriesRequestedModelId() {
        assertEquals(
            LlmDownloadState.Downloading("gemma-medium", 0L, 0L),
            resolve(WorkInfo.State.ENQUEUED, requestedId = "gemma-medium"),
        )
    }

    @Test
    fun blockedWithRequest_carriesRequestedModelId() {
        assertEquals(
            LlmDownloadState.Downloading("gemma-medium", 0L, 0L),
            resolve(WorkInfo.State.BLOCKED, requestedId = "gemma-medium"),
        )
    }

    @Test
    fun runningWithoutProgressId_fallsBackToRequestedId() {
        assertEquals(
            LlmDownloadState.Downloading("gemma-large", 1024L, 4096L),
            resolve(
                WorkInfo.State.RUNNING,
                progressModelId = "",
                bytesSoFar = 1024L,
                totalBytes = 4096L,
                requestedId = "gemma-large",
            ),
        )
    }

    @Test
    fun runningWithProgressId_prefersProgressId() {
        assertEquals(
            LlmDownloadState.Downloading("gemma-large", 2048L, 4096L),
            resolve(
                WorkInfo.State.RUNNING,
                progressModelId = "gemma-large",
                bytesSoFar = 2048L,
                totalBytes = 4096L,
                requestedId = "stale-id",
            ),
        )
    }

    @Test
    fun succeeded_isCompletedWithOutputId() {
        assertEquals(
            LlmDownloadState.Completed("gemma-small"),
            resolve(WorkInfo.State.SUCCEEDED, outputModelId = "gemma-small", requestedId = "gemma-small"),
        )
    }

    @Test
    fun failed_isFailedWithError() {
        assertEquals(
            LlmDownloadState.Failed("gemma-small", "network down"),
            resolve(WorkInfo.State.FAILED, outputModelId = "gemma-small", error = "network down"),
        )
    }

    @Test
    fun failedWithoutError_usesFallbackMessage() {
        assertEquals(
            LlmDownloadState.Failed("gemma-small", "Unknown error"),
            resolve(WorkInfo.State.FAILED, outputModelId = "gemma-small", error = null),
        )
    }

    @Test
    fun cancelled_isIdle() {
        assertEquals(
            LlmDownloadState.Idle,
            resolve(WorkInfo.State.CANCELLED, requestedId = "gemma-small"),
        )
    }
}
