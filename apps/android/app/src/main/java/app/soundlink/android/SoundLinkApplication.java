package app.soundlink.android;

import android.app.Application;
import android.app.NotificationManager;

public final class SoundLinkApplication extends Application {
  private static final String RETIRED_PROMPT_CHANNEL = "offline_reconnect";
  private static final String RETIRED_PROMPT_STATE = "offline_reconnect_prompt";
  private static final int RETIRED_PROMPT_ID = 47;

  @Override
  public void onCreate() {
    super.onCreate();
    Diagnostics.init(this);
    Diagnostics.event(
        this,
        "switching_preference_restored",
        "恢复已保存的手机自动切换设置",
        Diagnostics.fields(
            "enabled", HeadphoneControl.enabled(this), "auto_recovery", Recovery.enabled(this)),
        true);
    PhoneMuteGuard.recover(this, "应用启动恢复");
    if ("prompt".equals(Link.prefs(this).getString(OfflineReconnectFlow.PREFERENCE, null))) {
      Link.prefs(this).edit().putString(OfflineReconnectFlow.PREFERENCE, "auto").apply();
    }
    removeRetiredNotifications();
  }

  /** The reconnect prompt was removed; drop its channel and any stale state from older builds. */
  private void removeRetiredNotifications() {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.cancel(RETIRED_PROMPT_ID);
    manager.deleteNotificationChannel(RETIRED_PROMPT_CHANNEL);
    Link.prefs(this).edit().remove(RETIRED_PROMPT_STATE).apply();
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    Diagnostics.event(
        this, "memory_trim", "内存回调（不等于进程被杀）", Diagnostics.fields("level", level), true);
  }
}
