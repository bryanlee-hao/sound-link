package app.soundlink.android;

import android.bluetooth.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.provider.Settings;
import java.util.*;
import org.json.*;

/** Metadata only: no microphone capture, audio focus, playback or Bluetooth control. */
@android.annotation.SuppressLint("MissingPermission")
final class PhoneObservation {
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AudioManager audio;
  private final BluetoothAdapter adapter;
  private final Map<Integer, BluetoothProfile> profiles = new HashMap<>();
  private final Set<Integer> expected = new HashSet<>();
  private final String instance = UUID.randomUUID().toString();
  private boolean running, callbackRegistered;
  private long eventElapsed = -1;
  private final Runnable stateChanged;
  private String signalKey = "";
  private String previousMediaRoute;
  private int muteGeneration;
  private final PlaybackEdges edges =
      new PlaybackEdges(Set.of(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME));
  private final AudioManager.AudioPlaybackCallback callback =
      new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
          if (running) sample("audio_callback");
        }
      };
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (running) {
            sample("poll");
            main.postDelayed(this, 1000);
          }
        }
      };
  private final BluetoothProfile.ServiceListener listener =
      new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int kind, BluetoothProfile proxy) {
          main.post(
              () -> {
                if (!running) {
                  adapter.closeProfileProxy(kind, proxy);
                  return;
                }
                profiles.put(kind, proxy);
                sample("profile_callback");
              });
        }

        public void onServiceDisconnected(int kind) {
          main.post(() -> profiles.remove(kind));
        }
      };

  PhoneObservation(Context context, Runnable stateChanged) {
    this.stateChanged = stateChanged;
    this.context = context;
    audio = context.getSystemService(AudioManager.class);
    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    adapter = manager == null ? null : manager.getAdapter();
  }

  void start() {
    if (running) return;
    running = true;
    Diagnostics.event(
        context,
        "observation_start",
        "开始播放观察；重新建立边沿基线",
        Diagnostics.fields("instance", instance, "power", Diagnostics.power()),
        true);
    edges.resetBaseline();
    eventElapsed = -1;
    try {
      audio.registerAudioPlaybackCallback(callback, main);
      callbackRegistered = true;
    } catch (Exception ignored) {
    }
    if (adapter != null && Recovery.permissions(context)) {
      expected.add(BluetoothProfile.A2DP);
      expected.add(BluetoothProfile.HEADSET);
      try {
        if (adapter.isLeAudioSupported() == BluetoothStatusCodes.FEATURE_SUPPORTED)
          expected.add(BluetoothProfile.LE_AUDIO);
      } catch (Exception ignored) {
      }
      for (int kind : expected) {
        try {
          adapter.getProfileProxy(context, listener, kind);
        } catch (Exception ignored) {
        }
      }
    }
    poll.run();
  }

  void stop() {
    if (running)
      Diagnostics.event(
          context,
          "observation_stop",
          "停止播放观察；心跳失效或服务停止",
          Diagnostics.fields("instance", instance, "power", Diagnostics.power()),
          true);
    running = false;
    edges.resetBaseline();
    eventElapsed = -1;
    signalKey = "";
    main.removeCallbacks(poll);
    if (callbackRegistered) {
      audio.unregisterAudioPlaybackCallback(callback);
      callbackRegistered = false;
    }
    if (adapter != null)
      for (Map.Entry<Integer, BluetoothProfile> entry : profiles.entrySet())
        adapter.closeProfileProxy(entry.getKey(), entry.getValue());
    profiles.clear();
    expected.clear();
    Link.prefs(context).edit().remove("local_observation").remove("observation_elapsed").apply();
  }

  void refreshForConnection(boolean resync) {
    // Network retries must not erase local playback edges. The receiver uses stateSync.
    start();
    sample(resync ? "connection_resync" : "connection_sample");
  }

  void refreshForHandoff() {
    sample("handoff_verification");
  }

  // Read live profile proxies during a bounded handoff; avoids the 1-second cached sample lag.
  boolean connectedProfile(String address, boolean mediaOnly) {
    if (!running || adapter == null || !Recovery.permissions(context)) return false;
    try {
      BluetoothDevice device = adapter.getRemoteDevice(address);
      for (Map.Entry<Integer, BluetoothProfile> p : profiles.entrySet()) {
        if (mediaOnly
            && p.getKey() != BluetoothProfile.A2DP
            && p.getKey() != BluetoothProfile.LE_AUDIO) continue;
        if (p.getValue().getConnectionState(device) == BluetoothProfile.STATE_CONNECTED)
          return true;
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  private void sample(String source) {
    if (!running) return;
    try {
      JSONObject target = new JSONObject(Link.prefs(context).getString("observe_target", "{}"));
      JSONObject s =
          new JSONObject()
              .put("instance", instance)
              .put(
                  "localControlGeneration",
                  Link.prefs(context).getString("phone_local_generation", ""))
              .put(
                  "usageRecoveryToken",
                  new JSONObject(Link.prefs(context).getString("remote_observation", "{}"))
                      .optString("usageRecoveryToken"))
              .put("event", edges.event)
              .put("targetRevision", target.optInt("revision"))
              .put("playing", "unknown")
              .put("playback", "无法读取播放状态")
              .put("connection", "unknown")
              .put("connectionDetail", "等待蓝牙权限或音频 profile")
              .put("route", "未知")
              .put("protectedAudio", true)
              .put("audioReadable", false)
              .put("media", false)
              .put("ageMs", 0);
      try {
        List<AudioPlaybackConfiguration> configurations = audio.getActivePlaybackConfigurations();
        Map<Integer, Integer> usages = new TreeMap<>();
        for (AudioPlaybackConfiguration config : configurations)
          usages.merge(config.getAudioAttributes().getUsage(), 1, Integer::sum);
        boolean music = audio.isMusicActive();
        int previousEvent = edges.event;
        String routeKey =
            audio
                .getAudioDevicesForAttributes(
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .stream()
                .map(d -> d.getType() + ":" + d.getAddress())
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
        boolean reset = previousMediaRoute != null && !previousMediaRoute.equals(routeKey);
        previousMediaRoute = routeKey;
        if (muteGeneration != PhoneMuteGuard.generation()) {
          muteGeneration = PhoneMuteGuard.generation();
          reset = true;
        }
        if (reset) edges.resetBaseline();
        // Mute/route reconfiguration gaps are not a user starting new playback.
        if (!PhoneMuteGuard.active() && !(music && usages.isEmpty())) edges.update(usages);
        if (edges.event != previousEvent) eventElapsed = SystemClock.elapsedRealtime();
        int mode = audio.getMode();
        boolean protectedAudio = mode != AudioManager.MODE_NORMAL;
        for (int usage : usages.keySet())
          if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
              || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
              || usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
              || usage == AudioAttributes.USAGE_UNKNOWN) protectedAudio = true;
        if (music && usages.isEmpty()) protectedAudio = true;
        List<String> labels = new ArrayList<>();
        for (Map.Entry<Integer, Integer> usage : usages.entrySet())
          labels.add(usageName(usage.getKey()) + " ×" + usage.getValue());
        String playback =
            labels.isEmpty() ? (music ? "系统报告音乐活动；配置不可见" : "未检测到播放") : String.join("、", labels);
        if (protectedAudio) playback += "；通话/通信或未知用途保护";
        playback += "（匿名用途，不能识别具体播放器）";
        List<String> outputs = new ArrayList<>();
        for (AudioDeviceInfo d :
            audio.getAudioDevicesForAttributes(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()))
          outputs.add(d.getProductName().toString());
        s.put("playing", !usages.isEmpty() || music ? "active" : "idle")
            .put("playback", playback)
            .put("event", edges.event)
            .put("protectedAudio", protectedAudio)
            .put(
                "audioReadable",
                !(music && usages.isEmpty()) && !usages.containsKey(AudioAttributes.USAGE_UNKNOWN))
            .put("media", edges.media)
            // Current playback is distinct from the edge authorizing automatic takeover.
            // Muting resets edge baselines, but must not invalidate an explicit prompt choice.
            .put("mediaActive", usages.containsKey(AudioAttributes.USAGE_MEDIA)
                || usages.containsKey(AudioAttributes.USAGE_GAME))
            .put("musicActive", music)
            .put("playbackConfigurationsEmpty", usages.isEmpty())
            .put("audioModeNormal", mode == AudioManager.MODE_NORMAL)
            .put("routeKey", routeKey)
            .put(
                "route",
                outputs.isEmpty()
                    ? "媒体预期路由未知"
                    : "媒体预期路由：" + String.join("、", outputs) + "（非逐播放器实际路由）");
      } catch (Exception e) {
        edges.resetBaseline();
        eventElapsed = -1;
        s.put("playback", "播放观察受限：" + e.getClass().getSimpleName())
            .put("playing", "unknown")
            .put("audioReadable", false)
            .put("protectedAudio", true);
      }
      String address = target.optString("phoneAddress");
      if (address.isEmpty()) s.put("connectionDetail", "尚未确认手机上的目标耳机");
      else if (adapter != null && Recovery.permissions(context) && adapter.isEnabled()) {
        BluetoothDevice d = adapter.getRemoteDevice(address);
        if (d.getBondState() != BluetoothDevice.BOND_BONDED)
          s.put("connectionDetail", "目标未在系统蓝牙中配对");
        else {
          boolean connected = false,
              complete = !expected.isEmpty() && profiles.keySet().containsAll(expected);
          List<String> connectedKinds = new ArrayList<>();
          for (Map.Entry<Integer, BluetoothProfile> profile : profiles.entrySet()) {
            int state = profile.getValue().getConnectionState(d);
            if (state == BluetoothProfile.STATE_CONNECTED) {
              connected = true;
              connectedKinds.add(
                  profile.getKey() == BluetoothProfile.A2DP
                      ? "A2DP"
                      : profile.getKey() == BluetoothProfile.HEADSET ? "通话" : "LE Audio");
            } else if (state != BluetoothProfile.STATE_DISCONNECTED) complete = false;
          }
          s.put("connection", connected ? "connected" : complete ? "disconnected" : "unknown")
              .put(
                  "connectionDetail",
                  target.optString("phoneName")
                      + " · "
                      + (connected
                          ? String.join(" / ", connectedKinds)
                          : complete ? "音频 profile 均断开" : "等待完整 profile 状态"));
        }
      }
      if (eventElapsed >= 0) s.put("eventElapsed", eventElapsed);
      Diagnostics.playback(context, s, source);
      Link.prefs(context)
          .edit()
          .putString("local_observation", s.toString())
          .putLong("observation_elapsed", SystemClock.elapsedRealtime())
          .putInt(
              "observation_boot",
              Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0))
          .apply();
      String key =
          s.optInt("event")
              + "|"
              + s.optString("playing")
              + "|"
              + s.optBoolean("media")
              + "|"
              + s.optBoolean("mediaActive")
              + "|"
              + s.optBoolean("protectedAudio")
              + "|"
              + s.optBoolean("audioReadable")
              + "|"
              + s.optString("connection")
              + "|"
              + s.optString("route");
      if (!key.equals(signalKey)) {
        signalKey = key;
        stateChanged.run();
      }
    } catch (Exception ignored) {
      Link.prefs(context).edit().remove("local_observation").apply();
    }
  }

  static JSONObject wire(Context c) {
    try {
      android.content.SharedPreferences p = Link.prefs(c);
      long age = SystemClock.elapsedRealtime() - p.getLong("observation_elapsed", -100000);
      if (age < 0
          || age >= 10000
          || p.getInt("observation_boot", -1)
              != Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0))
        return null;
      JSONObject sample = new JSONObject(p.getString("local_observation", "")).put("ageMs", age);
      long eventAt = sample.optLong("eventElapsed", -1);
      sample.remove("eventElapsed");
      if (eventAt >= 0) sample.put("eventAgeMs", SystemClock.elapsedRealtime() - eventAt);
      return sample;
    } catch (Exception e) {
      return null;
    }
  }

  static String usageName(int usage) {
    return switch (usage) {
      case AudioAttributes.USAGE_MEDIA -> "媒体";
      case AudioAttributes.USAGE_GAME -> "游戏";
      case AudioAttributes.USAGE_NOTIFICATION,
          AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
          AudioAttributes.USAGE_NOTIFICATION_EVENT ->
          "通知/铃声";
      case AudioAttributes.USAGE_ALARM -> "闹钟";
      case AudioAttributes.USAGE_VOICE_COMMUNICATION,
          AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING ->
          "通话/通信";
      case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "系统提示音";
      case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "无障碍";
      case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "导航";
      default -> "未知用途 " + usage;
    };
  }
}
