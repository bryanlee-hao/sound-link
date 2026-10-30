package app.soundlink.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real-device preview: separate notification ID, inert actions, no pairing/volume/Bluetooth writes.
 * Run manually with adb am instrument; do NOT use Gradle device fixtures that uninstall the app.
 */
class LiveXiaomiIslandTest {
    @Test fun previewAndReportCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = instrumentation.targetContext
        val manager = c.getSystemService(NotificationManager::class.java)
        assertTrue("Reconnect notifications must already be enabled", OfflineReconnectNotification.available(c))
        val report = JSONObject().put("protocol", XiaomiIsland.protocol(c))
            .put("appIdConfigured", BuildConfig.XIAOMI_APP_ID.isNotEmpty())
        try {
            val request = Bundle().apply { putString("package", c.packageName) }
            val result = c.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus", null, request)
            report.put("focusPermission", result?.getBoolean("canShowFocus", false) ?: false)
        } catch (e: Exception) {
            report.put("focusPermissionError", e.javaClass.simpleName)
        }
        val token = UUID.randomUUID().toString()
        val events = LinkedBlockingQueue<String>()
        val actionName = "app.soundlink.android.ISLAND_PREVIEW"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("token") == token) events.add(intent.getStringExtra("choice")!!)
            }
        }
        c.registerReceiver(receiver, IntentFilter(actionName).apply { addDataScheme("soundlink") }, Context.RECEIVER_NOT_EXPORTED)
        val id = 48
        try {
            fun action(choice: String, label: Int): Notification.Action {
                val intent = Intent(actionName).setPackage(c.packageName)
                    .setData(Uri.parse("soundlink://island-preview/$token/$choice"))
                    .putExtra("token", token).putExtra("choice", choice)
                return Notification.Action.Builder(null, c.getString(label), PendingIntent.getBroadcast(c, id,
                    intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
            }
            val ignore = action("ignore", R.string.offline_ignore)
            val connect = action("connect", R.string.offline_connect)
            val deadline = SystemClock.elapsedRealtime() + 5000
            val title = c.getString(R.string.offline_prompt_title, "AirPods · 预览")
            fun preview(first: Boolean): Notification {
                val detail = OfflineReconnectNotification.detail(c, true, false, deadline)
                val notification = Notification.Builder(c, OfflineReconnectNotification.CHANNEL)
                    .setSmallIcon(R.drawable.ic_soundlink).setContentTitle(title).setContentText(detail)
                    .setStyle(Notification.BigTextStyle().bigText(detail)).setOnlyAlertOnce(true)
                    .setDeleteIntent(ignore.actionIntent)
                    .setTimeoutAfter(maxOf(1, deadline - SystemClock.elapsedRealtime()))
                    .addAction(ignore).addAction(connect).build()
                XiaomiIsland.decorate(c, notification, XiaomiIsland.protocol(c), title, detail, true,
                    deadline - SystemClock.elapsedRealtime(), first)
                return notification
            }
            manager.notify("island-preview", id, preview(true))
            SystemClock.sleep(650)
            val active = manager.activeNotifications.singleOrNull { it.id == id && it.tag == "island-preview" }
            assertNotNull("The ordinary notification fallback must remain present", active)
            report.put("notificationPosted", true).put("islandExtrasSubmitted",
                active!!.notification.extras.containsKey(XiaomiIslandPayload.PARAM))
            SystemClock.sleep(500)
            manager.notify("island-preview", id, preview(false))
            // Leave the preview visible for its full deadline without attaching UiAutomation.
            SystemClock.sleep(maxOf(0, deadline - SystemClock.elapsedRealtime()))
            connect.actionIntent.send()
            assertEquals("connect", events.poll(2, TimeUnit.SECONDS))
            ignore.actionIntent.send()
            assertEquals("ignore", events.poll(2, TimeUnit.SECONDS))
            report.put("previewActionsDelivered", true)
        } finally {
            manager.cancel("island-preview", id)
            c.unregisterReceiver(receiver)
            File(c.getExternalFilesDir(null), "xiaomi-island-preview.json").writeText(report.toString(2))
        }
    }
}
