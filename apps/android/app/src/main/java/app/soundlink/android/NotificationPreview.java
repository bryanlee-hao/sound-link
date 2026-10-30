package app.soundlink.android;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.UUID;
import org.json.JSONObject;

/** User-triggered diagnostics in the main APK. No audio or Bluetooth changes. */
final class NotificationPreview {
  private static final String TAG = "offline-preview";
  private static final int ID = 48;
  private static NotificationPreview current;
  private static int status;

  private final Context context;
  private final NotificationManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final OfflineReconnectFlow flow = new OfflineReconnectFlow();
  private boolean registered;
  private int lastSeconds = -1;
  private final Runnable send = this::send;
  private final Runnable tick = this::tick;
  private final BroadcastReceiver actions = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent intent) {
      if (current != NotificationPreview.this || !flow.matches(intent.getStringExtra("token"))) return;
      if (SystemClock.elapsedRealtime() >= flow.deadline()) {
        finish(R.string.notification_preview_timeout);
      } else if (OfflineReconnectNotification.CONNECT.equals(intent.getAction())) {
        finish(R.string.notification_preview_connected);
      } else if (OfflineReconnectNotification.IGNORE.equals(intent.getAction())) {
        finish(R.string.notification_preview_ignored);
      }
    }
  };

  private NotificationPreview(Context c) {
    context = c.getApplicationContext();
    manager = context.getSystemService(NotificationManager.class);
  }

  static boolean running() { return current != null; }
  static int status() { return status; }

  static void start(Context c) {
    if (!OfflineReconnectFlow.PROMPT_ENABLED || current != null) return;
    current = new NotificationPreview(c);
    status = R.string.notification_preview_scheduled;
    current.main.postDelayed(current.send, 3000);
  }

  private void send() {
    if (current != this) return;
    try {
      if (!OfflineReconnectNotification.available(context)) {
        finish(R.string.offline_notifications_needed);
        return;
      }
      for (var notification : manager.getActiveNotifications()) {
        if (notification.getId() == OfflineReconnectNotification.ID && notification.getTag() == null) {
          finish(R.string.notification_preview_busy);
          return;
        }
      }
      flow.begin("preview-" + UUID.randomUUID(), "preview", new JSONObject(), SystemClock.elapsedRealtime());
      IntentFilter filter = new IntentFilter();
      filter.addAction(OfflineReconnectNotification.CONNECT);
      filter.addAction(OfflineReconnectNotification.IGNORE);
      filter.addDataScheme("soundlink");
      context.registerReceiver(actions, filter, Context.RECEIVER_NOT_EXPORTED);
      registered = true;
      status = R.string.notification_preview_sent;
      tick();
      if (current == this)
        Diagnostics.record(context, "重连通知测试", "主应用发送普通通知，未改变音量或蓝牙");
    } catch (Exception e) {
      finish(R.string.notification_preview_failed);
    }
  }

  private void tick() {
    if (current != this) return;
    long remaining = flow.deadline() - SystemClock.elapsedRealtime();
    if (remaining <= 0) {
      finish(R.string.notification_preview_timeout);
      return;
    }
    try {
      if (!OfflineReconnectNotification.available(context)) {
        finish(R.string.offline_notifications_needed);
        return;
      }
      int seconds = (int) ((remaining + 999) / 1000);
      if (seconds != lastSeconds) {
        Notification notification = OfflineReconnectNotification.build(context, flow, false);
        PendingIntent open = PendingIntent.getActivity(context, ID,
            new Intent(context, MainActivity.class).putExtra("notification_preview", true),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notification = Notification.Builder.recoverBuilder(context, notification)
            .setSubText(context.getString(R.string.notification_preview_label)).setContentIntent(open).build();
        manager.notify(TAG, ID, notification);
        lastSeconds = seconds;
      }
      main.postDelayed(tick, 100);
    } catch (Exception e) {
      finish(R.string.notification_preview_failed);
    }
  }

  private void finish(int result) {
    if (current != this) return;
    main.removeCallbacks(send);
    main.removeCallbacks(tick);
    manager.cancel(TAG, ID);
    if (registered) context.unregisterReceiver(actions);
    flow.clear();
    current = null;
    status = result;
    Diagnostics.record(context, "重连通知测试", context.getString(result));
  }
}
