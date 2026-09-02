package dev.heyari.ari.bugreport

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import java.io.ByteArrayOutputStream

private const val TAG = "WindowScreenshot"

/** Enough to read a screen on, without shipping a 12-megapixel PNG. */
private const val MAX_EDGE = 1280

/**
 * Grabs what is on screen right now, as a PNG.
 *
 * [PixelCopy] rather than drawing the view hierarchy to a canvas: it copies
 * what the compositor actually produced, so anything backed by a surface — a
 * map, a video, a rendered card — comes out as the user saw it rather than as
 * a blank rectangle.
 *
 * Asynchronous, so the caller navigates from [onResult] rather than before it;
 * capturing after the report screen has opened would photograph the report
 * screen instead of the bug.
 *
 * Hands back null rather than throwing. A missing screenshot costs one
 * optional attachment; a crash in the bug reporter costs the report.
 */
fun captureWindow(activity: Activity, onResult: (ByteArray?) -> Unit) {
    val window = activity.window
    val view = window?.decorView
    if (window == null || view == null || view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }

    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runCatching {
        PixelCopy.request(window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                onResult(encode(bitmap))
            } else {
                Log.w(TAG, "PixelCopy returned $result — sending the report without a screenshot")
                onResult(null)
            }
        }, Handler(Looper.getMainLooper()))
    }.onFailure {
        Log.w(TAG, "could not capture the screen", it)
        onResult(null)
    }
}

private fun encode(bitmap: Bitmap): ByteArray? = runCatching {
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest > MAX_EDGE) {
        val ratio = MAX_EDGE.toFloat() / longest
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}.getOrNull()
