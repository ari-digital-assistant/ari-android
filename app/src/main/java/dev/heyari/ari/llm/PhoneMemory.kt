package dev.heyari.ari.llm

import kotlin.math.ceil

/** How an on-device model suits the phone's memory (ari-android#42). */
enum class MemoryFit { Fits, Tight, TooBig }

/**
 * Grades on-device models against the phone's memory.
 *
 * A model needs its [LlmModel.runtimeBytes] while it answers, and Android
 * plus the apps people keep open want 3 to 4 GB on top. A model fits with
 * 3.5 GB to spare and is tight with 2 GB. Below that it's too big: Android
 * would keep closing other apps, or Ari, to make room for it.
 */
object PhoneMemory {
    private const val GIB = 1L shl 30
    private const val FITS_HEADROOM = 7 * GIB / 2
    private const val TIGHT_HEADROOM = 2 * GIB

    /** The memory sizes phones are sold with, in GB. */
    private val SOLD_SIZES_GB = listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)

    /**
     * The memory the phone was sold with, from Android's total
     * (`ActivityManager.MemoryInfo.totalMem`), or null when that's unknown.
     * Android reports less than the box says, since the kernel and firmware
     * keep some back, so this rounds up to the next size phones come in:
     * 7.2 GB reported is an 8 GB phone.
     */
    fun soldGb(totalBytes: Long): Int? {
        if (totalBytes <= 0) return null
        val gb = totalBytes.toDouble() / GIB
        return SOLD_SIZES_GB.firstOrNull { it >= gb } ?: ceil(gb).toInt()
    }

    fun fit(model: LlmModel, phoneGb: Int): MemoryFit {
        val phone = phoneGb * GIB
        return when {
            model.runtimeBytes + FITS_HEADROOM <= phone -> MemoryFit.Fits
            model.runtimeBytes + TIGHT_HEADROOM <= phone -> MemoryFit.Tight
            else -> MemoryFit.TooBig
        }
    }

    /** The smallest phone, in GB, that [model] isn't too big for. */
    fun minimumGb(model: LlmModel): Int =
        SOLD_SIZES_GB.firstOrNull { fit(model, it) != MemoryFit.TooBig } ?: SOLD_SIZES_GB.last()

    /** The biggest of [models] that fits comfortably, or null when none does. */
    fun recommended(models: List<LlmModel>, phoneGb: Int): LlmModel? =
        models.filter { fit(it, phoneGb) == MemoryFit.Fits }.maxByOrNull { it.runtimeBytes }
}
