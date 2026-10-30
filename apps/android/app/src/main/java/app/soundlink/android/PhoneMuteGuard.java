package app.soundlink.android;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;

/** Bounded STREAM_MUSIC mute, not a per-player gain envelope. Never writes volume indices. */
final class PhoneMuteGuard {
  private static final Handler timer = new Handler(Looper.getMainLooper());
  private static PowerManager.WakeLock wake;
  private static final MuteLease lease = new MuteLease();
  private static int generation;

  private static android.content.SharedPreferences state(Context c) {
    return c.getSharedPreferences("soundlink_mute_recovery", Context.MODE_PRIVATE);
  }

  static boolean active() {
    return lease.active();
  }

  static int generation() {
    return generation;
  }

  private static PendingIntent alarm(Context c, String token) {
    return PendingIntent.getBroadcast(
        c,
        46,
        new Intent(c, MuteRecoveryReceiver.class)
            .setData(Uri.parse("soundlink://mute/" + token)).putExtra("token", token),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void begin(Context c, String token) {
    begin(c, token, 5000);
  }

  static void begin(Context c, String token, long budget) {
    if (lease.active() || budget <= 0) return; // Never extend an existing guard's deadline.
    recover(c, "开始前恢复");
    if (!state(c).getString("token", "").isEmpty()) return;
    AudioManager audio = c.getSystemService(AudioManager.class);
    try {
      if (audio.getMode() != AudioManager.MODE_NORMAL
          || audio.isVolumeFixed()
          || audio.isStreamMute(AudioManager.STREAM_MUSIC)
          || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return;
      var outputs =
          audio.getAudioDevicesForAttributes(
              new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
      if (outputs.isEmpty()) return;
      for (AudioDeviceInfo d : outputs)
        if (d.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            && d.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) return;
      if (!state(c).edit().putString("token", token).commit()) return;
      lease.begin(token, SystemClock.elapsedRealtime(), Math.min(5000, budget));
      generation++;
      schedule(c, token);
      audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
      Diagnostics.event(
          c,
          "mute_guard",
          "媒体临时静音（最长 5 秒）",
          Diagnostics.fields(
              "id",
              token,
              "stage",
              "begin",
              "verified",
              audio.isStreamMute(AudioManager.STREAM_MUSIC)),
          true);
    } catch (Exception e) {
      release(c, token, "静音未完成：" + e.getClass().getSimpleName());
    }
  }

  /** Transfer an existing mute without unmuting or muting again; only an explicit choice calls this. */
  static void transfer(Context c, String from, String to, long budget) {
    if (!from.equals(lease.token()) || !from.equals(state(c).getString("token", ""))) return;
    if (!lease.canTransfer(from, SystemClock.elapsedRealtime()) || !muted(c, from)) {
      release(c, from, "等待已结束或用户已解除静音");
      return;
    }
    try {
      if (!state(c).edit().putString("token", to).commit()) {
        release(c, from, "无法保存静音交接");
        return;
      }
      c.getSystemService(AlarmManager.class).cancel(alarm(c, from));
      if (!lease.transfer(from, to, SystemClock.elapsedRealtime(), budget)) {
        recover(c, "静音交接已过期");
        return;
      }
      schedule(c, to);
    } catch (Exception e) {
      recover(c, "静音交接失败");
    }
  }

  static boolean muted(Context c, String token) {
    return lease.active() && token.equals(lease.token())
        && c.getSystemService(AudioManager.class).isStreamMute(AudioManager.STREAM_MUSIC);
  }

  /** A user unmute relinquishes ownership so a later manual mute is not undone by our cleanup. */
  static void respectUserUnmute(Context c) {
    if (lease.active() && (!muted(c, lease.token())
        || c.getSystemService(AudioManager.class).getMode() != AudioManager.MODE_NORMAL))
      recover(c, "用户已解除静音或音频模式已改变");
  }

  static String routeKey(Context c) {
    try {
      return c.getSystemService(AudioManager.class)
          .getAudioDevicesForAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA).build()).stream()
          .map(d -> d.getType() + ":" + d.getAddress()).sorted()
          .collect(java.util.stream.Collectors.joining("|"));
    } catch (Exception e) {
      return "";
    }
  }

  private static void schedule(Context c, String token) {
    timer.removeCallbacksAndMessages(null);
    if (wake != null && wake.isHeld()) wake.release();
    long duration = Math.max(1, lease.deadline() - SystemClock.elapsedRealtime());
    wake = c.getSystemService(PowerManager.class)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLink:mute-timeout");
    wake.acquire(duration + 500);
    timer.postDelayed(() -> release(c, token, "临时静音超时"), duration);
    // Inexact fallback if the process dies. Not a guaranteed five-second crash watchdog.
    c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP, lease.deadline(), alarm(c, token));
  }

  static void release(Context c, String token, String reason) {
    if (!token.equals(state(c).getString("token", ""))) return;
    recover(c, reason);
  }

  static void recover(Context c, String reason) {
    String token = state(c).getString("token", "");
    if (token.isEmpty()) return;
    try {
      AudioManager audio = c.getSystemService(AudioManager.class);
      // Preserve an explicit user unmute and never replay an old numeric volume on a new route.
      if (audio.isStreamMute(AudioManager.STREAM_MUSIC))
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
      boolean restored = !audio.isStreamMute(AudioManager.STREAM_MUSIC);
      Diagnostics.event(
          c,
          "mute_guard",
          "恢复媒体静音：" + reason,
          Diagnostics.fields("id", token, "stage", "restore", "verified", restored),
          true);
      if (restored) {
        state(c).edit().remove("token").commit();
        c.getSystemService(AlarmManager.class).cancel(alarm(c, token));
      }
    } catch (Exception e) {
      Diagnostics.record(c, "恢复媒体静音失败", e.getClass().getSimpleName() + "；保留恢复记录");
    } finally {
      lease.clear();
      generation++;
      timer.removeCallbacksAndMessages(null);
      if (wake != null && wake.isHeld()) wake.release();
      wake = null;
    }
  }
}
