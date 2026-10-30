package app.soundlink.android;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Optional decoration of the SAME standard notification; no audio, Bluetooth, or timers here. */
final class XiaomiIsland {
  static final String STATUS = "xiaomi_island_status";
  private static final AtomicBoolean querying = new AtomicBoolean();

  static int protocol(Context c) {
    try {
      return Settings.System.getInt(c.getContentResolver(), "notification_focus_protocol", 0);
    } catch (Exception e) {
      return 0;
    }
  }

  static void decorate(Context c, Notification notification, String title, String detail,
      boolean waiting, long remainingMs, boolean first) {
    // The user-facing focus switch is not Xiaomi's app/signature authorization.
    // Avoid submitting vendor templates before registration is configured.
    if (BuildConfig.XIAOMI_APP_ID.isEmpty()) return;
    decorate(c, notification, protocol(c), title, detail, waiting, remainingMs, first);
  }

  static void decorate(Context c, Notification notification, int protocolVersion,
      String title, String detail, boolean waiting, long remainingMs, boolean first) {
    if (protocolVersion < 3) return;
    try {
      Bundle extras = new Bundle();
      extras.putString(XiaomiIslandPayload.PARAM, XiaomiIslandPayload.create(title, detail,
          c.getString(waiting ? R.string.island_waiting : R.string.island_connecting),
          waiting, remainingMs, System.currentTimeMillis(), first,
          c.getString(R.string.offline_ignore), c.getString(R.string.offline_connect)).toString());
      Bundle pics = new Bundle();
      pics.putParcelable(XiaomiIslandPayload.ICON,
          Icon.createWithResource(c, R.drawable.ic_island_headphones));
      extras.putBundle(XiaomiIslandPayload.PICS, pics);
      if (waiting) {
        if (notification.actions == null || notification.actions.length != 2)
          throw new IllegalArgumentException("Both reconnect actions must be present");
        Bundle actions = new Bundle();
        // Reuse the exact same immutable actions, including the per-flow token, as the fallback.
        actions.putParcelable(XiaomiIslandPayload.IGNORE, notification.actions[0]);
        actions.putParcelable(XiaomiIslandPayload.CONNECT, notification.actions[1]);
        extras.putBundle(XiaomiIslandPayload.ACTIONS, actions);
      }
      notification.extras.putAll(extras);
    } catch (Exception e) {
      Diagnostics.record(c, "超级岛使用普通通知", e.getClass().getSimpleName());
    }
  }

  /** Xiaomi documents the permission provider as slow. Never query it on the mute/countdown path. */
  static void refreshStatus(Context context) {
    if (!querying.compareAndSet(false, true)) return;
    Context c = context.getApplicationContext();
    new Thread(() -> {
      try {
        int version = protocol(c);
        Object permission = "unavailable";
        if (version >= 3) {
          try {
            Bundle request = new Bundle();
            request.putString("package", c.getPackageName());
            Bundle result = c.getContentResolver().call(
                Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, request);
            permission = result == null ? "unknown" : result.getBoolean("canShowFocus", false);
          } catch (Exception e) {
            permission = "unknown:" + e.getClass().getSimpleName();
          }
        }
        String status = new JSONObject().put("protocol", version).put("focusPermission", permission)
            .put("appIdConfigured", !BuildConfig.XIAOMI_APP_ID.isEmpty())
            .put("debugBuild", BuildConfig.DEBUG).toString();
        if (!status.equals(Link.prefs(c).getString(STATUS, ""))) {
          Link.prefs(c).edit().putString(STATUS, status).apply();
          Diagnostics.record(c, "小米超级岛能力", status);
        }
      } catch (Exception e) {
        Diagnostics.record(c, "小米超级岛能力查询失败", e.getClass().getSimpleName());
      } finally {
        querying.set(false);
      }
    }, "soundlink-island-status").start();
  }
}
