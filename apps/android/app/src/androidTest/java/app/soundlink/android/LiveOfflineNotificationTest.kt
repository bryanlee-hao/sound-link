package app.soundlink.android

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real-device smoke test. Uses the production builder, an isolated notification ID and token.
 * No pairing fixtures, Bluetooth requests, audio writes, vendor extras or UiAutomation.
 * Run manually with adb am instrument; Gradle connected tests may uninstall the user's app.
 */
class LiveOfflineNotificationTest {
    @Test fun countdownActionsAndTimeout() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = c.getSystemService(NotificationManager::class.java)
        assertTrue("Enable reconnect notifications first", OfflineReconnectNotification.available(c))
        val token = "notification-preview-${UUID.randomUUID()}"
        val events = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("token") == token) events.add(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(OfflineReconnectNotification.CONNECT)
            addAction(OfflineReconnectNotification.IGNORE)
            addDataScheme("soundlink")
        }
        c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        val tag = "offline-preview"
        val id = 48
        val flow = OfflineReconnectFlow().apply {
            begin(token, "preview", JSONObject().put("instance", token).put("targetRevision", 1),
                SystemClock.elapsedRealtime())
        }
        val report = JSONObject()
        try {
            fun post(): Notification {
                val notification = OfflineReconnectNotification.build(c, flow, false)
                assertFalse(notification.extras.containsKey(XiaomiIslandPayload.PARAM))
                assertFalse(notification.extras.containsKey(XiaomiIslandPayload.PICS))
                assertFalse(notification.extras.containsKey(XiaomiIslandPayload.ACTIONS))
                manager.notify(tag, id, notification)
                return notification
            }
            val first = post()
            report.put("firstText", first.extras.getCharSequence(Notification.EXTRA_TEXT))
            SystemClock.sleep(150)
            assertTrue(manager.activeNotifications.any { it.id == id && it.tag == tag })
            report.put("ordinaryNotificationPosted", true)
            assertEquals(2, first.actions.size)
            // The real service rejects this isolated token; only our inert receiver handles it.
            first.actions[1].actionIntent.send()
            assertEquals(OfflineReconnectNotification.CONNECT, events.poll(2, TimeUnit.SECONDS)?.action)
            first.actions[0].actionIntent.send()
            assertEquals(OfflineReconnectNotification.IGNORE, events.poll(2, TimeUnit.SECONDS)?.action)
            first.deleteIntent.send()
            assertEquals(OfflineReconnectNotification.IGNORE, events.poll(2, TimeUnit.SECONDS)?.action)
            report.put("actionAndDismissIntentsDelivered", true)
            var lastText = first.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            while (flow.deadline() - SystemClock.elapsedRealtime() > 1000) {
                SystemClock.sleep(1000)
                if (SystemClock.elapsedRealtime() >= flow.deadline()) break
                val update = post()
                lastText = update.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            }
            assertNotEquals(report.getString("firstText"), lastText)
            report.put("lastText", lastText).put("countdownUpdated", true)
            SystemClock.sleep(maxOf(0, flow.deadline() - SystemClock.elapsedRealtime()))
            val expiryCheck = SystemClock.elapsedRealtime() + 2000
            while (manager.activeNotifications.any { it.id == id && it.tag == tag }
                && SystemClock.elapsedRealtime() < expiryCheck) SystemClock.sleep(100)
            assertFalse(manager.activeNotifications.any { it.id == id && it.tag == tag })
            report.put("timedOut", true)
        } finally {
            manager.cancel(tag, id)
            c.unregisterReceiver(receiver)
            File(c.getExternalFilesDir(null), "offline-notification-preview.json").writeText(report.toString(2))
        }
    }
}
