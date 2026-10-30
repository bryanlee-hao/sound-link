package app.soundlink.android;

import android.app.*;
import android.bluetooth.*;
import android.companion.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** Android 17 public BluetoothDevice.connect, scoped to the user's companion association. */
@android.annotation.SuppressLint("MissingPermission")
final class HeadphoneControl {
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private String active = "";
  private PowerManager.WakeLock wake;
  private final Runnable changed;
  private long connectStarted, edgeToRequestMs;
  private final PhoneObservation observer;
  private JSONObject timings = new JSONObject();
  private String timingID = "";
  private BroadcastReceiver linkReceiver;

  interface LocalCompletion {
    void complete(boolean success, String message);
  }

  private LocalCompletion localCompletion;
  private boolean localSuccess;
  private String localMessage = "独立接管已取消";

  boolean busy() {
    return !active.isEmpty();
  }

  void connectLocal(JSONObject sample, LocalCompletion done) {
    connectLocal(sample, null, 5000, done);
  }

  void connectLocal(JSONObject sample, String promptMuteToken, long budget, LocalCompletion done) {
    try {
      JSONObject cmd =
          new JSONObject()
              .put("id", "phone-local-" + UUID.randomUUID())
              .put("action", "connect")
              .put("revision", target(context).getInt("revision"))
              .put("address", target(context).getString("phoneAddress"))
              .put("instance", sample.getString("instance"))
              .put("event", sample.getInt("event"));
      boolean valid = promptMuteToken == null ? valid(cmd, true)
          : ready(context) && Link.prefs(context).getBoolean("running", false)
              && HandoffCommand.explicitConnectMatches(cmd, target(context), PhoneObservation.wire(context),
                  PhoneMuteGuard.muted(context, promptMuteToken));
      if (busy() || !valid || budget <= 0 || budget > 5000) {
        done.complete(false, "连接前状态已变化");
        return;
      }
      close();
      localCompletion = done;
      localSuccess = false;
      localMessage = "独立接管已取消";
      executeConnect(cmd, budget, promptMuteToken);
    } catch (Exception e) {
      done.complete(false, "独立接管准备失败：" + e.getClass().getSimpleName());
    }
  }

  private void mark(String stage) {
    if (timingID.isEmpty() || timings.has(stage)) return;
    long elapsed = Math.max(0, SystemClock.elapsedRealtime() - connectStarted);
    try {
      timings.put(stage, elapsed);
    } catch (JSONException ignored) {
    }
    Diagnostics.event(
        context,
        "headphone_timing",
        stage + " · 请求后 " + elapsed + " ms",
        Diagnostics.fields(
            "id", timingID, "stage", stage, "sinceRequestMs", elapsed, "pollIntervalMs", 250),
        true);
  }

  private boolean outputPresent(String address) {
    try {
      for (AudioDeviceInfo device :
          context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
        if (address.equalsIgnoreCase(device.getAddress())
            && (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
    } catch (Exception ignored) {
    }
    return false;
  }

  private void observeLink(String address) {
    linkReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent intent) {
            BluetoothDevice device =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device != null && address.equalsIgnoreCase(device.getAddress()))
              mark("acl_connected_broadcast");
          }
        };
    // Bluetooth system broadcasts may originate outside the system UID. This is timing only;
    // every success still requires live profile and media-route reads, never broadcast trust.
    context.registerReceiver(
        linkReceiver,
        new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
        Context.RECEIVER_EXPORTED);
  }

  HeadphoneControl(Context c, Runnable changed, PhoneObservation observer) {
    context = c;
    this.changed = changed;
    this.observer = observer;
  }

  static JSONObject target(Context c) {
    try {
      return new JSONObject(Link.prefs(c).getString("observe_target", "{}"));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  static boolean authorized(Context c) {
    if (!Recovery.permissions(c)) return false;
    String address = target(c).optString("phoneAddress");
    if (address.isEmpty()) return false;
    try {
      for (AssociationInfo a : c.getSystemService(CompanionDeviceManager.class).getMyAssociations())
        if (a.getDeviceMacAddress() != null
            && address.equalsIgnoreCase(a.getDeviceMacAddress().toString())) return true;
    } catch (Exception ignored) {
    }
    return false;
  }

  private static String peerPin(Context c) {
    try {
      return new JSONObject(Link.prefs(c).getString("peer", "{}")).getString("pin");
    } catch (Exception e) {
      return "unpaired";
    }
  }

  static boolean enabled(Context c) {
    return Link.prefs(c).getBoolean("handoff_enabled", false);
  }

  static boolean disable(Context c) {
    if (!Link.prefs(c).edit().putBoolean("handoff_enabled", false).commit()) {
      message(c, "无法保存暂停设置，请重试");
      return false;
    }
    message(c, "手机自动切换已暂停 · 重启后保持暂停");
    return true;
  }

  static boolean ready(Context c) {
    return target(c).optString("pendingSide").isEmpty()
        && !target(c).optString("macAddress").isEmpty()
        && authorized(c)
        && enabled(c)
        && peerPin(c).equals(Link.prefs(c).getString("handoff_pin", ""))
        && target(c).optInt("revision") == Link.prefs(c).getInt("handoff_revision", -1);
  }

  static void enable(android.app.Activity a, Runnable done) {
    JSONObject t = target(a);
    String address = t.optString("phoneAddress");
    int revision = t.optInt("revision");
    String pairingGeneration = PairingLifecycle.generation(a);
    if (address.isEmpty() || !t.optString("pendingSide").isEmpty() || !Recovery.permissions(a)) {
      message(a, "请先确认目标耳机并允许附近设备权限");
      return;
    }
    Runnable save =
        () -> {
          if (!pairingGeneration.equals(PairingLifecycle.generation(a)) || !Link.prefs(a).contains("peer")
              || revision != target(a).optInt("revision")
              || !address.equals(target(a).optString("phoneAddress"))) return;
          boolean saved =
              Link.prefs(a)
                  .edit()
                  .putBoolean("handoff_enabled", true)
                  .putInt("handoff_revision", revision)
                  .putString("handoff_pin", peerPin(a))
                  .commit();
          if (!saved) {
            message(a, "无法保存自动切换设置，请重试");
            done.run();
            return;
          }
          message(a, "手机自动切换已开启并保存；在 Mac 开启自动切换后协同生效");
          done.run();
        };
    if (authorized(a)) {
      save.run();
      return;
    }
    try {
      a.getSystemService(CompanionDeviceManager.class)
          .associate(
              new AssociationRequest.Builder()
                  .addDeviceFilter(new BluetoothDeviceFilter.Builder().setAddress(address).build())
                  .setSingleDevice(true)
                  .build(),
              a.getMainExecutor(),
              new CompanionDeviceManager.Callback() {
                @Override
                public void onAssociationPending(IntentSender sender) {
                  try {
                    a.startIntentSenderForResult(sender, 31, null, 0, 0, 0);
                  } catch (Exception e) {
                    onFailure(e.getMessage());
                  }
                }

                @Override
                public void onAssociationCreated(AssociationInfo info) {
                  if (!pairingGeneration.equals(PairingLifecycle.generation(a))
                      || !Link.prefs(a).contains("peer")) {
                    try { a.getSystemService(CompanionDeviceManager.class).disassociate(info.getId()); }
                    catch (Exception error) { Diagnostics.record(a, "过期耳机授权", error.getClass().getSimpleName()); }
                    return;
                  }
                  if (info.getDeviceMacAddress() != null
                      && address.equalsIgnoreCase(info.getDeviceMacAddress().toString()))
                    save.run();
                }

                @Override
                public void onFailure(CharSequence error) {
                  message(a, "耳机授权未完成：" + error);
                  done.run();
                }
              });
    } catch (Exception e) {
      message(a, "无法申请耳机控制：" + e.getMessage());
    }
  }

  static void message(Context c, String text) {
    Link.prefs(c).edit().putString("handoff_local_status", text).apply();
    Diagnostics.record(c, "耳机流转", text);
  }

  static JSONObject wire(Context c) {
    try {
      PhonePause.capture(c);
      return new JSONObject()
          .put("ready", ready(c))
          .put("revision", target(c).optInt("revision"))
          .put("result", new JSONObject(Link.prefs(c).getString("handoff_result", "{}")))
          .put(
              "localTakeover", new JSONObject(Link.prefs(c).getString("phone_local_control", "{}")))
          .put("localRecoveryToken", Link.prefs(c).getString("phone_local_recovery_token", ""));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  void receive(JSONObject data, String epoch, long latency, long receivedAt) {
    if (data == null) return;
    Link.prefs(context).edit().putString("handoff_remote", data.toString()).apply();
    JSONObject cmd = data.optJSONObject("command");
    if (!data.optBoolean("enabled") || !ready(context)) {
      close();
      return;
    }
    if (localCompletion != null)
      return; // An authenticated reconnect must not replace a local transaction.
    if (cmd == null) {
      close();
      return;
    }
    // A response initiated before local takeover must not execute after that takeover finishes.
    if (!Link.prefs(context)
        .getString("phone_local_generation", "")
        .equals(data.optString("phoneLocalGeneration"))) return;
    String key = cmd.optString("id") + ":" + cmd.optString("action");
    if (key.equals(Link.prefs(context).getString("handoff_consumed", ""))) return;
    long budget =
        HandoffCommand.budget(cmd, epoch, latency, SystemClock.elapsedRealtime() - receivedAt);
    if (budget <= 0 || !valid(cmd, false)) return;
    // Commit consumption before hardware effects, including across process death.
    if (!Link.prefs(context).edit().putString("handoff_consumed", key).commit()) return;
    if ("pause".equals(cmd.optString("action"))) {
      result(cmd, "pause_handled", PhonePause.pause(context, cmd), false);
      return;
    }
    if (!"connect".equals(cmd.optString("action")) || !valid(cmd, true)) return;
    close();
    executeConnect(cmd, budget);
  }

  private void executeConnect(JSONObject cmd, long budget) {
    executeConnect(cmd, budget, null);
  }

  private void executeConnect(JSONObject cmd, long budget, String promptMuteToken) {
    String key = cmd.optString("id") + ":connect";
    active = key;
    connectStarted = SystemClock.elapsedRealtime();
    edgeToRequestMs = -1;
    timings = new JSONObject();
    timingID = cmd.optString("id");
    try {
      long deadline = SystemClock.elapsedRealtime() + budget;
      wake =
          context
              .getSystemService(PowerManager.class)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLink:handoff");
      wake.acquire(budget); // Only this finite hardware transaction; never idle keep-alive.
      BluetoothDevice d =
          context
              .getSystemService(BluetoothManager.class)
              .getAdapter()
              .getRemoteDevice(cmd.getString("address"));
      String address = cmd.getString("address");
      try {
        observeLink(address);
      } catch (Exception e) {
        mark("acl_observer_unavailable");
      }
      boolean initialProfile = observer.connectedProfile(address, false);
      boolean initialOutput = outputPresent(address), initialRoute = mediaRoute(address);
      String originalRoute = PhoneMuteGuard.routeKey(context);
      connectStarted = SystemClock.elapsedRealtime();
      if (initialProfile) mark("profile_preexisting");
      if (initialOutput) mark("output_preexisting");
      if (initialRoute) mark("route_preexisting");
      JSONObject initial = PhoneObservation.wire(context);
      edgeToRequestMs = initial == null ? -1 : initial.optLong("eventAgeMs", -1);
      Diagnostics.event(
          context,
          "headphone_connect_requested",
          "提交耳机连接请求 · 起播后 " + edgeToRequestMs + " ms",
          Diagnostics.fields(
              "id",
              cmd.getString("id"),
              "event_age_ms",
              edgeToRequestMs,
              "request_elapsed_ms",
              connectStarted),
          true);
      if (promptMuteToken == null) PhoneMuteGuard.begin(context, key);
      else PhoneMuteGuard.transfer(context, promptMuteToken, key,
          Math.max(0, deadline - SystemClock.elapsedRealtime()));
      mark("connect_requested");
      int code = d.connect();
      mark("connect_api_returned");
      if (code != BluetoothStatusCodes.SUCCESS) {
        result(cmd, "failed", "蓝牙连接请求失败（" + code + "）", false);
        close();
        return;
      }
      message(context, "正在接管 · 等待目标耳机与媒体路由");
      main.post(
          new Runnable() {
            public void run() {
              if (!active.equals(key)) return;
              PhoneMuteGuard.respectUserUnmute(context);
              JSONObject currentPlayback = PhoneObservation.wire(context);
              if (currentPlayback != null && "idle".equals(currentPlayback.optString("playing"))
                  || !originalRoute.equals(PhoneMuteGuard.routeKey(context)) && !mediaRoute(address))
                PhoneMuteGuard.release(context, key, "播放已停止或音频输出已改变");
              // The OS already accepted connect. Continue read-only verification through
              // route/config changes; this path never issues another connect or changes audio.
              if (!ready(context)
                  || !Link.prefs(context).getBoolean("running", false)
                  || !HandoffCommand.sameTarget(
                      cmd, target(context), PhoneObservation.wire(context))) {
                result(cmd, "failed", "控制授权、目标或观察实例已变化", false);
                close();
                return;
              }
              if (SystemClock.elapsedRealtime() >= deadline) {
                result(cmd, "failed", "连接或媒体路由超时，不重试", false);
                close();
                return;
              }
              boolean profile = observer.connectedProfile(address, false);
              boolean mediaProfile = observer.connectedProfile(address, true);
              boolean output = outputPresent(address), route = mediaRoute(address);
              if (profile) mark("profile_connected_observed");
              if (mediaProfile) mark("media_profile_connected_observed");
              if (output) mark("audio_output_observed");
              if (route) mark("media_route_observed");
              if (profile && route) {
                mark("route_verified");
                observer.refreshForHandoff();
                result(cmd, "connected", "目标音频 profile 与系统媒体预期路由已确认", true);
                close();
                return;
              }
              main.postDelayed(this, 250);
            }
          });
    } catch (Exception e) {
      result(cmd, "failed", e.getClass().getSimpleName() + ": " + e.getMessage(), false);
      close();
    }
  }

  private boolean valid(JSONObject cmd, boolean connecting) {
    JSONObject t = target(context), s = PhoneObservation.wire(context);
    return ready(context)
        && Link.prefs(context).getBoolean("running", false)
        && HandoffCommand.matches(cmd, t, s, connecting);
  }

  boolean localRouteReady() {
    String address = target(context).optString("phoneAddress");
    return !address.isEmpty() && observer.connectedProfile(address, false) && mediaRoute(address);
  }

  boolean targetRouteSelected() {
    return mediaRoute(target(context).optString("phoneAddress"));
  }

  private boolean mediaRoute(String address) {
    try {
      for (AudioDeviceInfo d :
          context
              .getSystemService(AudioManager.class)
              .getAudioDevicesForAttributes(
                  new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()))
        if (address.equalsIgnoreCase(d.getAddress())
            && (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
    } catch (Exception ignored) {
    }
    return false;
  }

  private void result(JSONObject cmd, String stage, String message, boolean verified) {
    if (localCompletion != null) {
      localSuccess = verified && "connected".equals(stage);
      localMessage = message;
      Diagnostics.event(
          context,
          "phone_local_result",
          message,
          Diagnostics.fields(
              "id",
              cmd.optString("id"),
              "success",
              localSuccess,
              "requestToResultMs",
              SystemClock.elapsedRealtime() - connectStarted,
              "timingsMs",
              timings),
          true);
      return;
    }
    try {
      Link.prefs(context)
          .edit()
          .putString(
              "handoff_result",
              new JSONObject()
                  .put("id", cmd.getString("id"))
                  .put("revision", cmd.getInt("revision"))
                  .put("stage", stage)
                  .put("message", message)
                  .put("routeVerified", verified)
                  .put("timingsMs", "pause_handled".equals(stage) ? new JSONObject() : timings)
                  .put("verificationPollMs", 250)
                  .put("edgeToRequestMs", "pause_handled".equals(stage) ? -1 : edgeToRequestMs)
                  .put(
                      "requestToResultMs",
                      "pause_handled".equals(stage) || connectStarted == 0
                          ? -1
                          : SystemClock.elapsedRealtime() - connectStarted)
                  .toString())
          .apply();
    } catch (Exception ignored) {
    }
    message(context, message);
    changed.run();
  }

  void close() {
    LocalCompletion done = localCompletion;
    localCompletion = null;
    if (!active.isEmpty()) PhoneMuteGuard.release(context, active, "交接验证结束或取消");
    if (!active.isEmpty()) mark("verification_closed");
    timingID = "";
    if (linkReceiver != null) {
      try {
        context.unregisterReceiver(linkReceiver);
      } catch (Exception ignored) {
      }
      linkReceiver = null;
    }
    active = "";
    main.removeCallbacksAndMessages(null);
    if (wake != null && wake.isHeld()) wake.release();
    wake = null;
    if (done != null) done.complete(localSuccess, localMessage);
  }
}
