package app.soundlink.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryTest {
    @Test
    fun uprightPhoneDoesNotRotateSensorCorrectedPreviewAgain() {
        for (sensor in listOf(90, 270)) {
            val frame = previewGeometry(1280, 720, sensor, 0, 360f, 640f)
            assertEquals(360f, frame.width, .01f)
            assertEquals(640f, frame.height, .01f)
            assertEquals(0f, frame.rotation, .01f)
        }
    }

    @Test
    fun rotatedDisplaysStayUprightAndFillWithoutStretching() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                val frame = previewGeometry(1280, 720, sensor, display, 411f, 823f)
                val swapped = display % 180 != 0
                assertTrue((if (swapped) frame.height else frame.width) >= 410.99f)
                assertTrue((if (swapped) frame.width else frame.height) >= 822.99f)
                assertEquals(
                    if (sensor % 180 != 0) 720f / 1280 else 1280f / 720,
                    frame.width / frame.height,
                    .001f,
                )
                assertEquals(-display.toFloat(), frame.rotation, .01f)
            }
        }
    }
}
