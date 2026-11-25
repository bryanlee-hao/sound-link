package app.soundlink.android

import android.Manifest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

/** Hardware-independent audio integration; only on a disposable emulator. */
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
        }
    }

    @After fun cleanup() {
        if (!fixtureAllowed) return
        main {
            PhoneMuteGuard.recover(context, "Test cleanup")
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
}
