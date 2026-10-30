package app.soundlink.android;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** Revision-ordered snapshots and automatic matching shared by UI, heartbeats and push events. */
final class HeadphoneSelection {
  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static boolean busy;
  private static volatile long retryAfter;

  @android.annotation.SuppressLint("MissingPermission")
  static List<BluetoothDevice> devices(Context context) {
    List<BluetoothDevice> result = new ArrayList<>();
    if (!Recovery.permissions(context)) return result;
    try {
      var adapter = context.getSystemService(BluetoothManager.class).getAdapter();
      if (adapter == null || !adapter.isEnabled()) return result;
      for (BluetoothDevice d : adapter.getBondedDevices())
        if (d.getBluetoothClass() != null && d.getBluetoothClass().getMajorDeviceClass()
            == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) result.add(d);
      result.sort((a, b) -> name(a).compareToIgnoreCase(name(b)));
    } catch (SecurityException ignored) { }
    return result;
  }

  @android.annotation.SuppressLint("MissingPermission")
  static String name(BluetoothDevice device) {
    String name = device.getName();
    return name == null || name.isEmpty() ? "Bluetooth Audio" : name;
  }

  static boolean receive(Context c, JSONObject observation, String generation) {
    if (observation == null) return false;
    synchronized (PairingLifecycle.class) {
      if (!generation.equals(PairingLifecycle.generation(c)) || !Link.prefs(c).contains("peer")) return false;
      JSONObject next = observation.optJSONObject("target");
      if (next == null || !HeadphoneMatch.acceptsSnapshot(HeadphoneControl.target(c), next)) return false;
      Link.prefs(c).edit().putString("observe_target", next.toString())
          .putString("remote_observation", observation.toString()).apply();
      // A confirmed target may reuse an existing OS grant, but never enables a paused switch.
      if (next.optString("pendingSide").isEmpty() && !next.optString("macAddress").isEmpty()
          && HeadphoneControl.authorized(c)) {
        try {
          String pin = new JSONObject(Link.prefs(c).getString("peer", "{}")).getString("pin");
          Link.prefs(c).edit().putInt("handoff_revision", next.optInt("revision"))
              .putString("handoff_pin", pin).apply();
        } catch (Exception ignored) { }
      }
    }
    match(c);
    return true;
  }

  @android.annotation.SuppressLint("MissingPermission")
  static synchronized void match(Context context) {
    Context c = context.getApplicationContext();
    JSONObject target = HeadphoneControl.target(c);
    if (busy || SystemClock.elapsedRealtime() < retryAfter || !Link.prefs(c).contains("peer")
        || !"phone".equals(target.optString("pendingSide"))) return;
    BluetoothDevice candidate = devices(c).stream()
        .filter(d -> HeadphoneMatch.automatic(target, d.getAddress())).findFirst().orElse(null);
    if (candidate == null) return;
    String generation = PairingLifecycle.generation(c);
    busy = true;
    worker.execute(() -> {
      try {
        // The revision and pairing generation are checked again after waiting in the queue.
        if (!generation.equals(PairingLifecycle.generation(c))
            || target.optInt("revision") != HeadphoneControl.target(c).optInt("revision")) return;
        JSONObject body = new JSONObject().put("revision", target.getInt("revision"))
            .put("mode", "confirm").put("address", candidate.getAddress()).put("name", name(candidate));
        JSONObject result = new Link.Client(new JSONObject(Link.prefs(c).getString("peer", "{}")))
            .selectHeadphone(c, body);
        receive(c, result.optJSONObject("observation"), generation);
        Diagnostics.record(c, "耳机自动匹配", "已同步 Mac 选择的已配对耳机");
      } catch (Exception error) {
        retryAfter = SystemClock.elapsedRealtime() + 5000;
        Diagnostics.record(c, "耳机匹配重试", error.getClass().getSimpleName());
      } finally {
        synchronized (HeadphoneSelection.class) { busy = false; }
      }
    });
  }
}
