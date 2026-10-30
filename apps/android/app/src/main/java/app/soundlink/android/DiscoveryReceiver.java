package app.soundlink.android;

import android.bluetooth.le.*;
import android.content.*;
import java.util.*;

public final class DiscoveryReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    if (!"app.soundlink.DISCOVERED".equals(intent.getAction()) || !Recovery.enabled(c)) return;
    int error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0);
    if (error != 0) {
      Diagnostics.event(c, "ble_scan_error", "系统扫描错误", Diagnostics.fields("code", error), true);
      Recovery.invalidateRegistration();
      Recovery.status(c, "系统蓝牙扫描受限（" + error + "）");
      return;
    }
    int type = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, 0);
    ArrayList<ScanResult> results =
        intent.getParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult.class);
    Diagnostics.event(
        c,
        "ble_scan_result",
        "系统蓝牙扫描回调",
        Diagnostics.fields("callback_type", type, "count", results == null ? 0 : results.size()),
        true);
    if (results == null || results.isEmpty()) return;
    if (type == ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
      Link.prefs(c).edit().putBoolean("ble_nearby", false).apply();
      return;
    }
    try {
      java.util.UUID uuid =
          DiscoveryProtocol.serviceUUID(
              new org.json.JSONObject(Link.prefs(c).getString("peer", "")).getString("pin"));
      for (ScanResult result : results)
        if (result.getScanRecord() != null
            && result.getScanRecord().getServiceUuids() != null
            && result.getScanRecord().getServiceUuids().contains(new android.os.ParcelUuid(uuid))) {
          Link.prefs(c).edit().putBoolean("ble_nearby", true).apply();
          Recovery.wake(c, "系统蓝牙广播", result.getDevice());
          break;
        }
    } catch (Exception e) {
      Recovery.status(c, "蓝牙发现状态需要重新启用");
    }
  }
}
