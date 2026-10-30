package app.soundlink.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.UUID;
import org.json.JSONObject;

/** Pairing changes are scoped to a generation, so late work cannot revoke a new pairing. */
final class PairingLifecycle {
  static synchronized void complete(Context c, JSONObject config, String pairingID) {
    config.remove("secret");
    config.remove("invite");
    config.remove("request");
    Link.prefs(c)
        .edit()
        .putString("peer", config.toString())
        .putString("pairing_id", pairingID)
        .putString("pairing_generation", UUID.randomUUID().toString())
        .remove("draft")
        .putBoolean("handoff_enabled", true)
        .putBoolean("setup_pending", true)
        .putBoolean("connect_after_pair", true)
        .commit();
  }

  static synchronized String generation(Context c) {
    String value = Link.prefs(c).getString("pairing_generation", "");
    if (value.isEmpty()) {
      value = UUID.randomUUID().toString();
      Link.prefs(c).edit().putString("pairing_generation", value).commit();
    }
    return value;
  }

  static synchronized boolean clear(Context c, String expected) {
    if (!expected.equals(generation(c)) || !Link.prefs(c).contains("peer")) return false;
    clearLocal(c);
    return true;
  }

  private static void clearLocal(Context c) {
    Recovery.forget(c);
    PhoneMuteGuard.recover(c, "解除配对");
    var edit = Link.prefs(c).edit();
    for (String key :
        new String[] {
          "peer",
          "draft",
          "handoff_enabled",
          "handoff_pin",
          "handoff_revision",
          "observe_target",
          "remote_observation",
          "local_observation",
          "handoff_remote",
          "handoff_result",
          "phone_local_control",
          "phone_local_failed",
          "phone_local_failure_binding",
          "phone_local_recovery_token",
          "ack_elapsed",
          "ack_wall",
          "connect_after_pair",
          "setup_pending",
          "pairing_id"
        }) edit.remove(key);
    edit.putString("pairing_generation", UUID.randomUUID().toString()).commit();
    Diagnostics.record(c, "解除配对", "本机配对及控制状态已清除");
  }

  /** Connection state only: app identity, appearance, update settings and logs survive. */
  static boolean connectionPreference(String key) {
    return java.util.Set.of("peer", "draft", "auto_enabled", "setup_pending", "connect_after_pair",
        "ble_address", "ble_nearby", "discovery_state", "state", "running", "sent", "received",
        "mac_count", "connections", "latency", "service_tick", "endpoint_revision", "gatt_wall",
        "address_source", "address_wall", "presence_wall", "observe_target", "remote_observation",
        "local_observation", "ack_elapsed", "ack_wall", "offline_reconnect_prompt").contains(key)
        || key.startsWith("association_") || key.startsWith("pairing_")
        || key.startsWith("handoff_") || key.startsWith("phone_local_") || key.startsWith("wake_")
        || key.startsWith("observation_");
  }

  static synchronized boolean reset(Context context) {
    Context c = context.getApplicationContext();
    notifyPeer(c);
    // Unlike ordinary unpair, reset also clears orphan state with no current peer.
    clearLocal(c);
    boolean removed = Recovery.forgetAllAssociations(c);
    var edit = Link.prefs(c).edit();
    for (String key : Link.prefs(c).getAll().keySet())
      if (connectionPreference(key)) edit.remove(key);
    boolean saved = edit.putString("pairing_generation", UUID.randomUUID().toString()).commit();
    Diagnostics.record(c, "重置连接", removed && saved ? "连接、耳机选择及伴生授权已清除" : "部分清理未完成，可再次重置");
    return removed && saved;
  }

  static synchronized void forget(Context context) {
    Context c = context.getApplicationContext();
    notifyPeer(c);
    clear(c, generation(c));
  }

  private static void notifyPeer(Context context) {
    Context c = context.getApplicationContext();
    String config = Link.prefs(c).getString("peer", "{}");
    String pairingID = Link.prefs(c).getString("pairing_id", "");
    String client = Link.clientID(c);
    // Capture credentials for one bounded send attempt; callers revoke locally immediately.
    new Thread(
            () -> {
              try {
                if (pairingID.isEmpty())
                  return; // Older peer has not advertised this capability yet.
                JSONObject body =
                    new JSONObject()
                        .put("v", 1)
                        .put("action", "unpair")
                        .put("client_id", client)
                        .put("pairing_id", pairingID);
                new Link.Client(new JSONObject(config))
                    .request("/v1/unpair", Link.signed(body), null);
              } catch (Exception ignored) {
                Diagnostics.record(c, "解除配对通知", "已尝试通知 Mac，不重试");
              }
            },
            "unpair-notice")
        .start();
  }

  static final class Events implements AutoCloseable {
    private volatile boolean closed;
    private final Thread thread;

    Events(Context context) {
      Context c = context.getApplicationContext();
      String generation = generation(c);
      thread =
          new Thread(
              () -> {
                while (!closed
                    && generation.equals(generation(c))
                    && Link.prefs(c).contains("peer")) {
                  try {
                    Link.Client client =
                        new Link.Client(new JSONObject(Link.prefs(c).getString("peer", "{}")));
                    client.authenticate(c);
                    while (!closed && generation.equals(generation(c))) {
                      JSONObject event =
                          client.request("/v1/events", new JSONObject(), client.token);
                      if ("target_changed".equals(event.optString("event"))
                          && !event.optString("pairing_id").isEmpty()
                          && event.optString("pairing_id").equals(Link.prefs(c).getString("pairing_id", ""))) {
                        HeadphoneSelection.receive(c, event.optJSONObject("observation"), generation);
                      }
                      if ("unpaired".equals(event.optString("event"))
                          && event
                              .optString("pairing_id")
                              .equals(Link.prefs(c).getString("pairing_id", ""))
                          && !event.optString("pairing_id").isEmpty()) {
                        new Handler(Looper.getMainLooper()).post(() -> clear(c, generation));
                        return;
                      }
                    }
                  } catch (Exception ignored) {
                    try {
                      Thread.sleep(10000);
                    } catch (InterruptedException interrupted) {
                      return;
                    }
                  }
                }
              },
              "peer-events");
      thread.start();
    }

    public void close() {
      closed = true;
      thread.interrupt();
    }
  }
}
