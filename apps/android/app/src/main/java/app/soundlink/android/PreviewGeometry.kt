package app.soundlink.android

internal data class PreviewGeometry(val width: Float, val height: Float, val rotation: Float)

/**
 * Camera2's SurfaceTexture already applies sensor orientation. Size the embedded surface in that
 * natural orientation, then compensate only for display rotation (never rotate twice).
 */
internal fun previewGeometry(
    bufferWidth: Int,
    bufferHeight: Int,
    sensorDegrees: Int,
    displayDegrees: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): PreviewGeometry {
    val sensorSwapsAxes = sensorDegrees % 180 != 0
    val naturalWidth = (if (sensorSwapsAxes) bufferHeight else bufferWidth).toFloat()
    val naturalHeight = (if (sensorSwapsAxes) bufferWidth else bufferHeight).toFloat()
    val displaySwapsAxes = displayDegrees % 180 != 0
    val displayedWidth = if (displaySwapsAxes) naturalHeight else naturalWidth
    val displayedHeight = if (displaySwapsAxes) naturalWidth else naturalHeight
    val scale = maxOf(viewportWidth / displayedWidth, viewportHeight / displayedHeight)
    return PreviewGeometry(naturalWidth * scale, naturalHeight * scale, -displayDegrees.toFloat())
}
