package app.soundlink.android;

import android.bluetooth.BluetoothAdapter;
import android.content.*;

public final class RecoveryReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    String action = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
        || BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
            && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
      if (Recovery.enabled(c)) {
        Diagnostics.record(
            c,
            "重新注册发现",
            Intent.ACTION_BOOT_COMPLETED.equals(action)
                ? "手机启动"
                : Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ? "应用升级" : "蓝牙开启");
        Recovery.invalidateRegistration();
        Recovery.arm(c);
      }
    }
  }
}
