package app.soundlink.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Real-device smoke test. Changes only appearance and restores its original value in finally. */
class LiveAppearanceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val automation
        get() = instrumentation.uiAutomation

    private fun text(id: Int) = context.getString(id)

    private fun root(): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.takeIf { it.packageName == context.packageName }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun find(label: String) =
        nodes(root()).firstOrNull {
            it.isVisibleToUser &&
                (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }

    private fun click(id: Int) {
        val label = text(id)
        repeat(10) {
            var node = find(label)
            if (node != null) {
                while (node != null && !node.isClickable) node = node.parent
                check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "Cannot click $label"
                }
                SystemClock.sleep(300)
                return
            }
            nodes(root())
                .firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        error("Missing visible action: $label")
    }

    private fun activity(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
        }
        return result
    }

    private fun capture(name: String) {
        SystemClock.sleep(250)
        val bitmap = automation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertFullHeight() {
        val scroll =
            nodes(root()).firstOrNull { it.isScrollable } ?: error("Missing scroll viewport")
        val bounds = Rect()
        scroll.getBoundsInScreen(bounds)
        var windowHeight = 0
        instrumentation.runOnMainSync { windowHeight = activityWindowHeight() }
        assertEquals("Scroll extends underneath the navigation bar", windowHeight, bounds.bottom)
    }

    private fun activityWindowHeight(): Int =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .first()
            .window
            .decorView
            .height

    @Test
    fun themesAboutAndEdgeToEdgePreserveExistingData() {
        val prefs = Link.prefs(context)
        val original = prefs.getString("interface_appearance", null)
        try {
            // Launch through the shell to avoid vendor restrictions on background activity starts.
            automation.executeShellCommand("am start -n ${context.packageName}/app.soundlink.android.MainActivity").use {
                descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
            waitFor("App did not become visible") { root() != null && activity() != null }
            repeat(3) { if (find(text(R.string.settings)) == null) click(R.string.back) }
            click(R.string.settings)
            assertFullHeight()
            for ((mode, label) in
                listOf(
                    "dark" to R.string.appearance_dark,
                    "light" to R.string.appearance_light,
                    "system" to R.string.system_language,
                )) {
                click(R.string.appearance)
                click(label)
                waitFor("Appearance was not saved") {
                    prefs.getString("interface_appearance", null) == mode
                }
                val before = activity()!!
                instrumentation.runOnMainSync { before.recreate() }
                waitFor("Activity did not recreate") {
                    activity()?.let { it !== before } == true && find(text(R.string.back)) != null
                }
                assertEquals(mode, prefs.getString("interface_appearance", null))
                instrumentation.runOnMainSync {
                    val current =
                        ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>()
                            .first()
                    val controller =
                        WindowCompat.getInsetsController(current.window, current.window.decorView)
                    if (mode != "system") {
                        assertEquals(mode == "light", controller.isAppearanceLightStatusBars)
                        assertEquals(mode == "light", controller.isAppearanceLightNavigationBars)
                    }
                }
                assertFullHeight()
                capture("live-settings-$mode")
                click(R.string.appearance)
                assertNotNull(find(text(label)))
                capture("live-appearance-dialog-$mode")
                click(R.string.cancel)
                assertEquals("Cancel preserves appearance", mode, prefs.getString("interface_appearance", null))
            }
            click(R.string.language)
            capture("live-language-dialog")
            click(R.string.cancel)
            click(R.string.about)
            waitFor("About version is missing") {
                find(text(R.string.message_version) + BuildConfig.VERSION_NAME) != null
            }
            assertNotNull(find(text(R.string.about_description)))
            capture("live-about")
            click(R.string.back)
        } finally {
            if (original == null) prefs.edit().remove("interface_appearance").commit()
            else prefs.edit().putString("interface_appearance", original).commit()
            SystemClock.sleep(300)
        }
    }
}
