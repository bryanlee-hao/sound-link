package app.soundlink.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONObject;

/** A dismissible prompt, separate from the service's ongoing connection notification. */
final class OfflineReconnectNotification {
  static final String CHANNEL = "offline_reconnect";
  static final int ID = 47;
  static final String CONNECT = "app.soundlink.android.OFFLINE_CONNECT";
  static final String IGNORE = "app.soundlink.android.OFFLINE_IGNORE";
  static final String STATE = "offline_reconnect_prompt";

  static void createChannel(Context c) {
    NotificationChannel channel = new NotificationChannel(CHANNEL,
        c.getString(R.string.offline_notification_channel), NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(null, null);
    channel.enableVibration(false);
    c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  static boolean available(Context c) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
    if (!manager.areNotificationsEnabled() || channel == null
        || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
    NotificationChannelGroup group = channel.getGroup() == null ? null
        : manager.getNotificationChannelGroup(channel.getGroup());
    return group == null || !group.isBlocked();
  }

  static Intent action(Context c, String action, String token) {
    return new Intent(action).setPackage(c.getPackageName())
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .setData(Uri.parse("soundlink://offline/" + token + "/" + action))
        .putExtra("token", token);
  }

  private static PendingIntent pending(Context c, String action, String token) {
    return PendingIntent.getBroadcast(c, 0, action(c, action, token),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static String detail(Context c, boolean waiting, boolean muted, long deadline) {
    int seconds = (int) Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
    return c.getResources().getQuantityString(waiting
        ? muted ? R.plurals.offline_wait_muted : R.plurals.offline_wait_unmuted
        : muted ? R.plurals.offline_connecting_muted : R.plurals.offline_connecting_unmuted,
        seconds, seconds);
  }

  /** Shared by the real prompt and the isolated real-device notification preview. */
  static Notification build(Context c, OfflineReconnectFlow flow, boolean muted) {
    String name = HeadphoneControl.target(c).optString("phoneName", c.getString(R.string.headphones));
    String title = c.getString(flow.waiting() ? R.string.offline_prompt_title
        : R.string.offline_connecting_title, name);
    String detail = detail(c, flow.waiting(), muted, flow.deadline());
    Notification.Builder builder = base(c, title, detail)
        .setTimeoutAfter(Math.max(1, flow.deadline() - SystemClock.elapsedRealtime()));
    if (flow.waiting()) {
      PendingIntent ignore = pending(c, IGNORE, flow.token());
      builder.setDeleteIntent(ignore)
          .addAction(new Notification.Action.Builder(null, c.getString(R.string.offline_ignore), ignore).build())
          .addAction(new Notification.Action.Builder(null, c.getString(R.string.offline_connect),
              pending(c, CONNECT, flow.token())).build());
    }
    // Super Island is deferred. Production prompts always use ordinary local notifications.
    return builder.build();
  }

  static void show(Context c, OfflineReconnectFlow flow, boolean muted) {
    Notification notification = build(c, flow, muted);
    c.getSystemService(NotificationManager.class).notify(ID, notification);
    try {
      String state = new JSONObject().put("token", flow.token()).put("waiting", flow.waiting())
          .put("deadline", flow.deadline())
          .put("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
          .put("detail", notification.extras.getCharSequence(Notification.EXTRA_TEXT)).toString();
      if (!state.equals(Link.prefs(c).getString(STATE, "")))
        Link.prefs(c).edit().putString(STATE, state).apply();
    } catch (Exception ignored) { }
  }

  private static Notification.Builder base(Context c, String title, String detail) {
    PendingIntent open = PendingIntent.getActivity(c, ID,
        new Intent(c, MainActivity.class).putExtra("offline_prompt", true),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_soundlink)
        .setContentTitle(title).setContentText(detail)
        .setStyle(new Notification.BigTextStyle().bigText(detail))
        .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false);
  }

  static void result(Context c, boolean success) {
    clear(c);
    if (!available(c)) return;
    c.getSystemService(NotificationManager.class).notify(ID,
        base(c, c.getString(success ? R.string.offline_success : R.string.offline_failed),
            c.getString(R.string.offline_result_detail)).setTimeoutAfter(3000).build());
  }

  static void clear(Context c) {
    c.getSystemService(NotificationManager.class).cancel(ID);
    Link.prefs(c).edit().remove(STATE).apply();
  }
}
