package app.soundlink.android;

import android.content.*;

public final class MuteRecoveryReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    PhoneMuteGuard.release(
        c, intent.getStringExtra("token") == null ? "" : intent.getStringExtra("token"), "系统恢复闹钟");
  }
}
