package app.soundlink.android;

import android.app.Application;

public final class SoundLinkApplication extends Application {
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
    if (!OfflineReconnectFlow.PROMPT_ENABLED
        && "prompt".equals(Link.prefs(this).getString(OfflineReconnectFlow.PREFERENCE, null))) {
      Link.prefs(this).edit().putString(OfflineReconnectFlow.PREFERENCE, "auto").apply();
    }
    OfflineReconnectNotification.createChannel(this);
    OfflineReconnectNotification.clear(this);
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    Diagnostics.event(
        this, "memory_trim", "内存回调（不等于进程被杀）", Diagnostics.fields("level", level), true);
  }
}
