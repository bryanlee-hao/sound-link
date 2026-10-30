package app.soundlink.android;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.companion.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;
import org.json.JSONObject;

/** Durable user intent + OS registrations. Discovery events never grant trust or audio control. */
@android.annotation.SuppressLint(
    "MissingPermission") // Callers check runtime grants; Bluetooth operations also catch
// revocation.
final class Recovery {
  private static boolean armed;

  static void invalidateRegistration() {
    armed = false;
  }

  static boolean enabled(Context c) {
    return Link.prefs(c).getBoolean("auto_enabled", false) && Link.prefs(c).contains("peer");
  }

  static boolean permissions(Context c) {
    return c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  static AssociationInfo association(Context c) {
    int id = Link.prefs(c).getInt("association_id", -1);
    try {
      JSONObject peer = new JSONObject(Link.prefs(c).getString("peer", ""));
      if (!peer.getString("pin").equals(Link.prefs(c).getString("association_pin", "")))
        return null;
      for (AssociationInfo info :
          c.getSystemService(CompanionDeviceManager.class).getMyAssociations())
        if (info.getId() == id) return info;
    } catch (Exception ignored) {
    }
    return null;
  }

  private static PendingIntent scanIntent(Context c) {
    return PendingIntent.getBroadcast(
        c,
        41,
        new Intent(c, DiscoveryReceiver.class).setAction("app.soundlink.DISCOVERED"),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
  }

  static synchronized void arm(Context c) {
    if (!enabled(c) || armed) return;
    if (!permissions(c)) {
      status(c, "需要附近设备权限");
      return;
    }
    AssociationInfo info = association(c);
    if (info == null) {
      status(c, "请先授权 Mac 伴生设备");
      return;
    }
    try {
      c.getSystemService(CompanionDeviceManager.class)
          .startObservingDevicePresence(
              new ObservingDevicePresenceRequest.Builder().setAssociationId(info.getId()).build());
      BluetoothAdapter adapter = c.getSystemService(BluetoothManager.class).getAdapter();
      if (adapter == null || !adapter.isEnabled()) {
        status(c, "等待手机蓝牙开启");
        return;
      }
      JSONObject peer = new JSONObject(Link.prefs(c).getString("peer", ""));
      // First-match/match-lost avoids waking the app for every advertisement.
      ScanSettings settings =
          new ScanSettings.Builder()
              .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
              .setCallbackType(
                  ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
              .build();
      BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
      scanner.stopScan(scanIntent(c));
      int result =
          scanner.startScan(
              List.of(
                  new ScanFilter.Builder()
                      .setServiceUuid(
                          new ParcelUuid(DiscoveryProtocol.serviceUUID(peer.getString("pin"))))
                      .build()),
              settings,
              scanIntent(c));
      armed = result == 0;
      status(c, result == 0 ? "自动发现已启用 · 等待 Mac 广播" : "伴生观察已启用 · 系统扫描受限（" + result + "）");
    } catch (Exception e) {
      status(c, "自动发现暂不可用 · " + ConnectionService.friendly(e));
    }
  }

  static void enable(Context c) {
    Link.prefs(c).edit().putBoolean("auto_enabled", true).apply();
    arm(c);
  }

  static void disable(Context c) {
    Diagnostics.record(c, "用户暂停", "撤销恢复入口并请求停止连接服务");
    armed = false;
    Link.prefs(c)
        .edit()
        .putBoolean("auto_enabled", false)
        .remove("association_request")
        .putString("discovery_state", "已暂停自动发现")
        .putString("state", "已由用户停止")
        .apply();
    try {
      BluetoothAdapter adapter = c.getSystemService(BluetoothManager.class).getAdapter();
      if (adapter != null && adapter.getBluetoothLeScanner() != null)
        adapter.getBluetoothLeScanner().stopScan(scanIntent(c));
    } catch (Exception ignored) {
    }
    try {
      AssociationInfo info = association(c);
      if (info != null)
        c.getSystemService(CompanionDeviceManager.class)
            .stopObservingDevicePresence(
                new ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(info.getId())
                    .build());
    } catch (Exception ignored) {
    }
    c.stopService(new Intent(c, ConnectionService.class));
    Diagnostics.record(c, "用户暂停", "停止连接及自动发现");
  }

  static void forget(Context c) {
    AssociationInfo info = association(c);
    disable(c);
    if (info != null)
      try {
        c.getSystemService(CompanionDeviceManager.class).disassociate(info.getId());
      } catch (Exception ignored) {
      }
    Link.prefs(c)
        .edit()
        .remove("association_id")
        .remove("association_pin")
        .remove("ble_address")
        .apply();
  }

  /** Remove only this app's grants, including stale Mac and headphone associations. */
  static boolean forgetAllAssociations(Context c) {
    try {
      CompanionDeviceManager manager = c.getSystemService(CompanionDeviceManager.class);
      for (AssociationInfo info : manager.getMyAssociations()) {
        try { manager.disassociate(info.getId()); }
        catch (Exception error) {
          Diagnostics.record(c, "重置伴生授权", error.getClass().getSimpleName());
        }
      }
      return manager.getMyAssociations().isEmpty();
    } catch (Exception error) {
      Diagnostics.record(c, "重置伴生授权", error.getClass().getSimpleName());
      return false;
    }
  }

  static synchronized void wake(Context c, String reason, BluetoothDevice device) {
    if (!enabled(c) || association(c) == null) {
      Diagnostics.wake(c, reason, false);
      return;
    }
    long now = SystemClock.elapsedRealtime();
    long last = Link.prefs(c).getLong("wake_elapsed", 0);
    if (now >= last && now - last < 4000) {
      Diagnostics.wake(c, reason, false);
      return;
    }
    Link.prefs(c)
        .edit()
        .putLong("wake_elapsed", now)
        .putString("wake_reason", reason)
        .putLong("wake_wall", System.currentTimeMillis())
        .apply();
    Diagnostics.wake(c, reason, true);
    try {
      Intent intent =
          new Intent(c, ConnectionService.class).setAction("WAKE").putExtra("reason", reason);
      if (device != null) intent.putExtra("device", device);
      c.startForegroundService(intent);
    } catch (Exception e) {
      Link.prefs(c).edit().putLong("wake_elapsed", 0).apply();
      status(c, "系统暂未允许后台连接 · 请打开 App");
      Diagnostics.record(c, "后台启动受限", e.getClass().getSimpleName());
    }
  }

  static void presence(Context c, int id, boolean present) {
    if (id != Link.prefs(c).getInt("association_id", -1)) return;
    Diagnostics.event(
        c,
        "companion_presence",
        present ? "系统伴生在场" : "系统伴生离场",
        Diagnostics.fields("association", id, "present", present),
        true);
    Link.prefs(c)
        .edit()
        .putBoolean("ble_nearby", present)
        .putLong("presence_wall", System.currentTimeMillis())
        .apply();
    if (present) {
      status(c, "系统发现 Mac 在附近");
      wake(c, "伴生设备在场", null);
    } else status(c, "系统未发现 Mac · 连接状态由心跳确认");
  }

  static void status(Context c, String value) {
    Link.prefs(c).edit().putString("discovery_state", value).apply();
  }

  static void associate(Activity activity, Runnable done) {
    try {
      JSONObject peer = new JSONObject(Link.prefs(activity).getString("peer", ""));
      String pin = peer.getString("pin");
      String request = UUID.randomUUID().toString();
      Link.prefs(activity).edit().putString("association_request", request).apply();
      BluetoothLeDeviceFilter filter =
          new BluetoothLeDeviceFilter.Builder()
              .setScanFilter(
                  new ScanFilter.Builder()
                      .setServiceUuid(new ParcelUuid(DiscoveryProtocol.serviceUUID(pin)))
                      .build())
              // CDM requires a nonempty excerpt. One character is safe for short aliases
              // and address fallbacks; the stable product name comes from the paired identity.
              .setRenameFromName("", " · ♫ " + discoveryName(pin), 0, 1)
              .build();
      AssociationRequest association =
          new AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build();
      status(activity, "在系统列表中选择 ♫ " + discoveryName(pin) + " 并确认");
      activity
          .getSystemService(CompanionDeviceManager.class)
          .associate(
              association,
              activity.getMainExecutor(),
              new CompanionDeviceManager.Callback() {
                @Override
                public void onAssociationPending(IntentSender sender) {
                  if (activity.isDestroyed()
                      || !request.equals(Link.prefs(activity).getString("association_request", "")))
                    return;
                  try {
                    activity.startIntentSenderForResult(sender, 30, null, 0, 0, 0);
                  } catch (Exception e) {
                    onFailure(e.getMessage());
                  }
                }

                @Override
                public void onAssociationCreated(AssociationInfo info) {
                  Context c = activity.getApplicationContext();
                  try {
                    JSONObject current = new JSONObject(Link.prefs(c).getString("peer", "{}"));
                    if (!request.equals(Link.prefs(c).getString("association_request", ""))
                        || !pin.equals(current.optString("pin"))) {
                      c.getSystemService(CompanionDeviceManager.class).disassociate(info.getId());
                      return;
                    }
                    Link.prefs(c)
                        .edit()
                        .putInt("association_id", info.getId())
                        .putString("association_pin", pin)
                        .remove("association_request")
                        .putString(
                            "ble_address",
                            info.getDeviceMacAddress() == null
                                ? ""
                                : info.getDeviceMacAddress().toString())
                        .apply();
                    enable(c);
                    Diagnostics.record(c, "系统关联", "Mac 伴生授权已保存");
                    BluetoothDevice device =
                        info.getDeviceMacAddress() == null
                            ? null
                            : c.getSystemService(BluetoothManager.class)
                                .getAdapter()
                                .getRemoteDevice(info.getDeviceMacAddress().toString());
                    wake(c, "首次伴生授权", device);
                    if (!activity.isDestroyed()) done.run();
                  } catch (Exception e) {
                    onFailure(e.getMessage());
                  }
                }

                @Override
                public void onFailure(CharSequence error) {
                  status(activity, "关联未完成 · " + error);
                  if (!activity.isDestroyed()) done.run();
                }
              });
    } catch (Exception e) {
      status(activity, "无法发起伴生关联 · " + ConnectionService.friendly(e));
      done.run();
    }
  }

  static String discoveryName(String pin) {
    return String.format(
        Locale.ROOT, "Sound Link %04d", Long.parseLong(pin.substring(0, 8), 16) % 10000);
  }
}
