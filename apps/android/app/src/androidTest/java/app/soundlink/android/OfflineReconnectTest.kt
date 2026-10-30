package app.soundlink.android

import android.Manifest
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Hardware-independent audio/notification integration; only on a disposable emulator. */
class OfflineReconnectTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private var originalVolume = 0
    private var originalMuted = false
    private var fixtureAllowed = false

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Before fun setup() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        fixtureAllowed = true
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        main {
            PhoneMuteGuard.recover(context, "Test setup")
            originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            originalMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
            OfflineReconnectNotification.createChannel(context)
            OfflineReconnectNotification.clear(context)
        }
    }

    @After fun cleanup() {
        if (!fixtureAllowed) return
        main {
            PhoneMuteGuard.recover(context, "Test cleanup")
            OfflineReconnectNotification.clear(context)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (originalMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    @Test fun fourthSecondConnectionSurvivesOldDeadlineThenReleases() {
        main { PhoneMuteGuard.begin(context, "wait-test") }
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        SystemClock.sleep(4000)
        main { PhoneMuteGuard.transfer(context, "wait-test", "connect-test", 5000) }
        SystemClock.sleep(1300)
        main { PhoneMuteGuard.release(context, "wait-test", "Old timeout or dismissal") }
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertTrue(PhoneMuteGuard.active())
        SystemClock.sleep(4000)
        instrumentation.waitForIdleSync()
        assertFalse(PhoneMuteGuard.active())
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun waitingTimeoutAndExplicitReleaseRestoreWithoutChangingVolume() {
        main { PhoneMuteGuard.begin(context, "timeout-test", 300) }
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        SystemClock.sleep(600)
        instrumentation.waitForIdleSync()
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        main {
            PhoneMuteGuard.begin(context, "ignore-test")
            PhoneMuteGuard.release(context, "ignore-test", "Ignore")
        }
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun originalMuteAndUserUnmuteAreNeverOverriddenByCleanup() {
        main {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            PhoneMuteGuard.begin(context, "already-muted")
            assertFalse(PhoneMuteGuard.active())
            PhoneMuteGuard.release(context, "already-muted", "Expired")
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            PhoneMuteGuard.begin(context, "user-override")
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            PhoneMuteGuard.respectUserUnmute(context)
            assertFalse(PhoneMuteGuard.active())
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            PhoneMuteGuard.transfer(context, "user-override", "late-connect", 5000)
            PhoneMuteGuard.release(context, "user-override", "Expired")
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        }
    }

    @Test fun mutedPlaybackRemainsReadableWithoutCreatingAnotherStartEvent() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(96000).build()
        val observer = PhoneObservation(context) { }
        try {
            track.write(ShortArray(48000), 0, 48000)
            track.setLoopPoints(0, 48000, -1)
            main { observer.start() }
            track.play()
            SystemClock.sleep(500)
            main { observer.refreshForHandoff() }
            val before = PhoneObservation.wire(context)!!
            assertTrue(before.optBoolean("mediaActive"))
            main { PhoneMuteGuard.begin(context, "playback-test") }
            SystemClock.sleep(500)
            main { observer.refreshForHandoff() }
            val muted = PhoneObservation.wire(context)!!
            assertTrue(HandoffCommand.promptPlayback(muted, PhoneMuteGuard.muted(context, "playback-test")))
            if (muted.optBoolean("playbackConfigurationsEmpty")) {
                assertFalse(HandoffCommand.audioReadable(muted))
                assertFalse(HandoffCommand.promptPlayback(muted, false))
            }
            assertEquals(before.optInt("event"), muted.optInt("event"))
            track.stop()
            SystemClock.sleep(300)
            main { observer.refreshForHandoff() }
            assertFalse(PhoneObservation.wire(context)!!.optBoolean("mediaActive"))
            assertFalse(HandoffCommand.promptPlayback(PhoneObservation.wire(context), true))
        } finally {
            track.release()
            main { observer.stop() }
        }
    }

    @Test fun notificationActionsAndDismissalCarryTheirOwnImmutableTokens() {
        val events = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { events.add(intent) }
        }
        val filter = IntentFilter().apply {
            addAction(OfflineReconnectNotification.CONNECT)
            addAction(OfflineReconnectNotification.IGNORE)
            addDataScheme("soundlink")
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        try {
            val sample = JSONObject().put("instance", "test").put("targetRevision", 1)
            val flow = OfflineReconnectFlow()
            flow.begin("first", "fixture", sample, SystemClock.elapsedRealtime())
            main { OfflineReconnectNotification.show(context, flow, true) }
            val manager = context.getSystemService(NotificationManager::class.java)
            val first = manager.activeNotifications.single { it.id == OfflineReconnectNotification.ID }.notification
            assertFalse(first.extras.containsKey(XiaomiIslandPayload.PARAM))
            assertFalse(first.extras.containsKey(XiaomiIslandPayload.ACTIONS))
            assertFalse(first.extras.containsKey(XiaomiIslandPayload.PICS))
            assertEquals(2, first.actions.size)
            assertTrue(first.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("5"))
            first.actions[1].actionIntent.send()
            assertEquals("first", events.poll(2, TimeUnit.SECONDS)?.getStringExtra("token"))
            flow.clear()
            flow.begin("second", "fixture", sample, SystemClock.elapsedRealtime())
            main { OfflineReconnectNotification.show(context, flow, true) }
            first.deleteIntent.send()
            val deleted = events.poll(2, TimeUnit.SECONDS)
            assertEquals(OfflineReconnectNotification.IGNORE, deleted?.action)
            assertEquals("first", deleted?.getStringExtra("token"))
            assertFalse(flow.matches(deleted?.getStringExtra("token")))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test fun islandReusesFallbackActionsAndLeavesOriginalNotificationUntouched() {
        val ignore = PendingIntent.getBroadcast(context, 101,
            OfflineReconnectNotification.action(context, OfflineReconnectNotification.IGNORE, "island-test"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val connect = PendingIntent.getBroadcast(context, 102,
            OfflineReconnectNotification.action(context, OfflineReconnectNotification.CONNECT, "island-test"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val standard = Notification.Builder(context, OfflineReconnectNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_soundlink).setContentTitle("Connect?").setContentText("5 seconds")
            .setTimeoutAfter(5000).setDeleteIntent(ignore)
            .addAction(Notification.Action.Builder(null, "Ignore", ignore).build())
            .addAction(Notification.Action.Builder(null, "Connect", connect).build()).build()
        val island = standard.clone()
        XiaomiIsland.decorate(context, island, 3, "Connect?", "Muted\n5 seconds", true, 5000, true)
        assertFalse(standard.extras.containsKey(XiaomiIslandPayload.PARAM))
        assertTrue(island.extras.containsKey(XiaomiIslandPayload.PARAM))
        assertEquals(standard.deleteIntent, island.deleteIntent)
        assertEquals(standard.timeoutAfter, island.timeoutAfter)
        val actions = island.extras.getBundle(XiaomiIslandPayload.ACTIONS)!!
        assertEquals(ignore, actions.getParcelable(XiaomiIslandPayload.IGNORE, Notification.Action::class.java)!!.actionIntent)
        assertEquals(connect, actions.getParcelable(XiaomiIslandPayload.CONNECT, Notification.Action::class.java)!!.actionIntent)
        val unsupported = standard.clone()
        XiaomiIsland.decorate(context, unsupported, 0, "Connect?", "Muted", true, 5000, true)
        assertFalse(unsupported.extras.containsKey(XiaomiIslandPayload.PARAM))
        val invalid = standard.clone()
        XiaomiIsland.decorate(context, invalid, 3, "Connect?", "Muted", true, 0, true)
        assertFalse(invalid.extras.containsKey(XiaomiIslandPayload.PARAM))
        val connecting = Notification.Builder(context, OfflineReconnectNotification.CHANNEL)
            .setSmallIcon(R.drawable.ic_soundlink).setContentTitle("Connecting").build()
        XiaomiIsland.decorate(context, connecting, 3, "Connecting", "Muted", false, 5000, false)
        assertFalse(connecting.extras.containsKey(XiaomiIslandPayload.ACTIONS))
        assertFalse(JSONObject(connecting.extras.getString(XiaomiIslandPayload.PARAM)!!)
            .getJSONObject("param_v2").has("textButton"))
    }
}
