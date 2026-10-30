package app.soundlink.android;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** User-visible phone name, independent of the hardware model or pairing identity. */
final class DeviceName {
  static String clean(String value) {
    if (value == null) return "";
    String name = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", "").trim();
    int length = name.codePointCount(0, name.length());
    return length > 64 ? name.substring(0, name.offsetByCodePoints(0, 64)).trim() : name;
  }

  static String choose(String system, String bluetooth) {
    String name = clean(bluetooth);
    if (name.isEmpty()) name = clean(system);
    return name.isEmpty() ? "Android" : name;
  }

  @android.annotation.SuppressLint("MissingPermission")
  static String read(Context context) {
    String system = "", bluetooth = "";
    try {
      system = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
    } catch (RuntimeException ignored) { }
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
      try {
        var manager = context.getSystemService(BluetoothManager.class);
        var adapter = manager == null ? null : manager.getAdapter();
        if (adapter != null) bluetooth = adapter.getName();
      } catch (RuntimeException ignored) { }
    }
    return choose(system, bluetooth);
  }
}
