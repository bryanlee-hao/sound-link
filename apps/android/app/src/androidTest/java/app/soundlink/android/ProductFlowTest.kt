package app.soundlink.android

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Run only on a disposable emulator: fixtures never contain real peer credentials. */
class ProductFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixtureAllowed = false

    @Before
    fun setup() {
        check(
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk")
        ) {
            "Use a disposable emulator"
        }
        fixtureAllowed = true
        Link.prefs(context).edit().clear().commit()
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags("en")
    }

    @After
    fun cleanup() {
        scenario?.close()
        if (fixtureAllowed) Link.prefs(context).edit().clear().commit()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        val node = compose.onNode(hasText(text) or hasContentDescription(text))
        runCatching { node.assertIsDisplayed() }.onFailure { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun peer() =
        JSONObject()
            .put("name", "Test Mac")
            .put("pin", "a".repeat(64))
            .put("id", "a".repeat(32))
            .put("host", "127.0.0.1")
            .put("port", 9)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun offlineReconnectDefaultsAndSelectionSurviveRecreation() {
        assertEquals(OfflineReconnectFlow.Mode.AUTO,
            OfflineReconnectFlow.Mode.parse(Link.prefs(context).getString(OfflineReconnectFlow.PREFERENCE, null)))
        launch()
        click("Settings")
        compose.onNodeWithText("Reconnect automatically").assertExists()
        click(context.getString(R.string.offline_reconnect))
        compose.onNodeWithText(context.getString(R.string.offline_prompt_disabled)).assertIsNotEnabled()
        click("Never")
        assertEquals("never", Link.prefs(context).getString(OfflineReconnectFlow.PREFERENCE, ""))
        scenario!!.recreate()
        compose.waitForIdle()
        click(context.getString(R.string.offline_reconnect))
        click("Reconnect automatically")
        assertEquals("auto", Link.prefs(context).getString(OfflineReconnectFlow.PREFERENCE, ""))
        screenshot("compose-offline-setting")
    }

    @Test
    fun downloadLinkAndOptionalPlaybackHelp() {
        launch()
        screenshot("compose-home")
        click("Install Mac App")
        if (BuildConfig.WEBSITE_URL.isEmpty()) {
            compose.onNodeWithText(context.getString(R.string.development_companion)).assertIsDisplayed()
        } else {
            compose.onNodeWithText("Sound Link for Mac").assertIsDisplayed()
            click(BuildConfig.WEBSITE_URL)
            assertEquals(BuildConfig.WEBSITE_URL,
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            click("OK")
        }
        screenshot("compose-download")
        click("Back")
        click("Settings")
        compose.onNodeWithText("Devices & Connection").assertExists()
        compose.onNodeWithText("General").assertExists()
        compose.onNodeWithText("Help & About").assertExists()
        screenshot("compose-settings")
        compose.onNodeWithContentDescription("Back").assertHasClickAction()
        compose.onNodeWithText("Previous Device Keeps Playing").assertDoesNotExist()
        click("Help & Diagnostics")
        click("Previous Device Keeps Playing")
        compose.onNodeWithText(context.getString(R.string.pause_explanation)).assertIsDisplayed()
    }

    @Test
    fun pairingDefaultsAndResumableSetup() {
        val p = peer().put("secret", "discard-me").put("request", "old")
        PairingLifecycle.complete(context, p, "pair-1")
        assertTrue(HeadphoneControl.enabled(context))
        assertTrue(Link.prefs(context).getBoolean("setup_pending", false))
        assertFalse(JSONObject(Link.prefs(context).getString("peer", "")!!).has("secret"))
        // Avoid starting any network/Bluetooth service in the fixture.
        Link.prefs(context).edit().remove("connect_after_pair").commit()
        launch()
        compose.onAllNodesWithText(context.getString(R.string.setup_bluetooth), substring = true).onFirst().assertIsDisplayed()
        screenshot("compose-onboarding")
        click("Not Now")
        compose.onNode(isToggleable()).assertIsOn()
        screenshot("compose-paired")
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun unpairIsLocalAndLateNotificationCannotDeleteNewPairing() {
        PairingLifecycle.complete(context, peer(), "pair-1")
        Link.prefs(context)
            .edit()
            .remove("connect_after_pair")
            .putBoolean("setup_pending", false)
            .commit()
        val old = PairingLifecycle.generation(context)
        launch()
        click("Manage")
        click("Unpair…")
        click("Unpair")
        compose.onNodeWithText("One pair. Both devices.").assertIsDisplayed()
        assertFalse(Link.prefs(context).contains("peer"))
        PairingLifecycle.complete(context, peer(), "pair-2")
        assertFalse(PairingLifecycle.clear(context, old))
        assertTrue(Link.prefs(context).contains("peer"))
    }

    @Test
    fun scannerUsesComposeAndReceivesCameraFrames() {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        ActivityScenario.launch(ScanActivity::class.java).use {
            compose.waitUntil(15000) {
                compose
                    .onAllNodesWithText(context.getString(R.string.scan_hint))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            screenshot("compose-scanner")
            compose.onNodeWithText("Cancel").assertIsDisplayed()
        }
    }

    @Test
    fun chineseDownloadAndDiagnostics() {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags("zh")
        launch()
        click("安装 Mac App")
        compose.onNodeWithText("点击地址即可复制").assertIsDisplayed()
        screenshot("compose-download-zh")
        click("返回")
        click("设置")
        click("帮助与诊断")
        compose.onNodeWithText("导出日志").assertExists()
        screenshot("compose-diagnostics-zh")
    }
}
