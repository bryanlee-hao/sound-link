package app.soundlink.android;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class ConnectionService extends Service {
  private ScheduledExecutorService worker;
  private PairingLifecycle.Events events;
  private PhoneObservation observer;
  private final OfflinePhonePolicy localPolicy = new OfflinePhonePolicy();
  private final OfflineReconnectFlow promptFlow = new OfflineReconnectFlow();
  private PowerManager.WakeLock promptWake;
  private String promptDisplay = "";
  private final BroadcastReceiver promptActions = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
      handlePromptAction(intent.getAction(), intent.getStringExtra("token"));
    }
  };
  private final android.content.SharedPreferences.OnSharedPreferenceChangeListener policyChanges =
      (prefs, key) -> {
        if (OfflineReconnectFlow.PREFERENCE.equals(key) || "handoff_enabled".equals(key)
            || "observe_target".equals(key) || "peer".equals(key)) this.main.post(this::evaluateLocal);
      };
  private ScheduledFuture<?> heartbeatTask;
  private volatile boolean networkPaused;
  private String localBinding = "<unbound>", publishedLocal = "";

  private String currentBinding() {
    try {
      JSONObject t = HeadphoneControl.target(this);
      return new JSONObject(Link.prefs(this).getString("peer", "{}")).getString("pin")
          + ":"
          + t.getInt("revision")
          + ":"
          + t.getString("phoneAddress");
    } catch (Exception e) {
      return "";
    }
  }

  private void bindLocalPolicy() {
    String binding = currentBinding();
    if (binding.equals(localBinding)) return;
    if (promptFlow.waiting()) endPrompt("目标已改变");
    if (localPolicy.busy()) headphoneControl.close();
    localBinding = binding;
    boolean failed =
        binding.equals(Link.prefs(this).getString("phone_local_failure_binding", ""))
            && Link.prefs(this).getBoolean("phone_local_failed", false);
    localPolicy.bind(binding, failed);
    publishLocal();
  }

  private boolean publishLocal() {
    try {
      JSONObject value =
          new JSONObject()
              .put("active", localPolicy.busy() || promptFlow.waiting())
              .put("blocked", localPolicy.blocked())
              .put("status", promptFlow.waiting() ? "等待用户选择是否连接耳机" : localPolicy.status())
              .put("recoveryToken", localPolicy.recoveryToken());
      String text = value.toString();
      if (text.equals(publishedLocal)) return true;
      boolean saved =
          Link.prefs(this)
              .edit()
              .putString("phone_local_control", text)
              .putString("phone_local_recovery_token", localPolicy.recoveryToken())
              .putBoolean("phone_local_failed", localPolicy.blocked())
              .putString("phone_local_failure_binding", localBinding)
              .commit();
      if (saved) publishedLocal = text;
      return saved;
    } catch (Exception e) {
      return false;
    }
  }

  private void localGeneration() {
    Link.prefs(this)
        .edit()
        .putString("phone_local_generation", java.util.UUID.randomUUID().toString())
        .commit();
  }

  private void observationChanged() {
    main.post(this::evaluateLocal); // Never re-enter PhoneObservation.sample from its callback.
    requestFastPing();
  }

  private void evaluateLocal() {
    if (stopped) return;
    bindLocalPolicy();
    JSONObject sample = PhoneObservation.wire(this);
    boolean authorized = HeadphoneControl.ready(this) && bluetoothAvailable();
    if (!authorized && localPolicy.busy()) headphoneControl.close();
    long now = SystemClock.elapsedRealtime();
    boolean online = lastAck > 0 && now - lastAck < 10000;
    updatePrompt(sample, online, authorized, now);
    if (sample == null) return;
    boolean take = localPolicy.observe(sample, online, authorized,
        headphoneControl.busy() || promptFlow.active(), now);
    if (take) {
      switch (offlineMode()) {
        case NEVER -> { /* The playback edge is still consumed; changing modes cannot replay it. */ }
        case PROMPT -> beginPrompt(sample);
        case AUTO -> connectOffline(sample, null);
      }
    } else publishLocal();
    if (networkPaused) {
      state("网络重试已暂停 · " + localPolicy.status());
      if (!localPolicy.retainObservation() || !authorized) {
        stopReason = "网络重试已结束，且无有效本地使用会话";
        stopSelf();
      }
    }
  }

  private OfflineReconnectFlow.Mode offlineMode() {
    return OfflineReconnectFlow.Mode.parse(Link.prefs(this)
        .getString(OfflineReconnectFlow.PREFERENCE, null));
  }

  private boolean bluetoothAvailable() {
    try {
      var adapter = getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
      return adapter != null && adapter.isEnabled();
    } catch (Exception e) { return false; }
  }

  private void beginPrompt(JSONObject sample) {
    if (!OfflineReconnectNotification.available(this)) {
      HeadphoneControl.message(this, getString(R.string.offline_notifications_needed));
      return;
    }
    String token = java.util.UUID.randomUUID().toString();
    promptFlow.begin(token, localBinding, sample, SystemClock.elapsedRealtime());
    localGeneration(); // Fence authenticated responses initiated before this decision window.
    if (!publishLocal()) {
      endPrompt("无法保存提示状态");
      return;
    }
    try {
      promptWake = getSystemService(PowerManager.class)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundLink:offline-choice");
      promptWake.acquire(5500);
      PhoneMuteGuard.begin(this, token, promptFlow.deadline() - SystemClock.elapsedRealtime());
      showPrompt();
      main.post(promptTick);
    } catch (Exception e) {
      endPrompt("无法发送连接提示");
    }
  }

  private void showPrompt() {
    boolean muted = PhoneMuteGuard.active() && getSystemService(android.media.AudioManager.class)
        .isStreamMute(android.media.AudioManager.STREAM_MUSIC);
    String display = promptFlow.waiting() + ":" + muted + ":"
        + Math.max(0, (promptFlow.deadline() - SystemClock.elapsedRealtime() + 999) / 1000);
    if (display.equals(promptDisplay)) return;
    OfflineReconnectNotification.show(this, promptFlow, muted);
    promptDisplay = display;
  }

  private final Runnable promptTick = new Runnable() {
    @Override public void run() {
      if (stopped || !promptFlow.active()) return;
      evaluateLocal();
      if (promptFlow.active()) main.postDelayed(this, 100);
    }
  };

  private void updatePrompt(JSONObject sample, boolean online, boolean authorized, long now) {
    if (!promptFlow.active()) return;
    PhoneMuteGuard.respectUserUnmute(this);
    if (promptFlow.waiting()) {
      if (!promptFlow.valid(sample, currentBinding(), online,
          authorized && localPolicy.allowsPrompt(), offlineMode(), now,
          PhoneMuteGuard.muted(this, promptFlow.token()))
          || !OfflineReconnectNotification.available(this)) {
        endPrompt("提示已到期或状态已改变");
        return;
      }
      if (headphoneControl.localRouteReady()) {
        endPrompt("耳机已连接");
        return;
      }
      if (promptFlow.routeChanged(PhoneMuteGuard.routeKey(this))
          && !headphoneControl.targetRouteSelected()) {
        endPrompt("音频输出已改变");
        return;
      }
    } else if (now >= promptFlow.deadline()) {
      // The connection executor owns cleanup after submission, including mute ownership.
      headphoneControl.close();
      return;
    }
    try { showPrompt(); }
    catch (Exception e) {
      if (promptFlow.waiting()) endPrompt("提示更新失败");
      // A submitted connection keeps its bounded verification even if notifications are revoked.
    }
  }

  private void handlePromptAction(String action, String token) {
    if (stopped || !promptFlow.waiting() || !promptFlow.matches(token)) return;
    // Refresh hardware/playback before accepting a user intent, not only the cached notification.
    observer.refreshForHandoff();
    evaluateLocal();
    if (!promptFlow.waiting() || !promptFlow.matches(token)) return;
    if (OfflineReconnectNotification.IGNORE.equals(action)) {
      endPrompt("用户忽略连接提示");
    } else if (OfflineReconnectNotification.CONNECT.equals(action)) {
      JSONObject sample = PhoneObservation.wire(this);
      if (sample == null || !"disconnected".equals(sample.optString("connection"))) return;
      if (!promptFlow.accept(token, SystemClock.elapsedRealtime())) return;
      releasePromptWake();
      connectOffline(sample, token);
    }
  }

  private void releasePromptWake() {
    if (promptWake != null && promptWake.isHeld()) promptWake.release();
    promptWake = null;
  }

  private void endPrompt(String reason) {
    if (!promptFlow.active()) return;
    String token = promptFlow.token();
    promptFlow.clear();
    promptDisplay = "";
    main.removeCallbacks(promptTick);
    releasePromptWake();
    PhoneMuteGuard.release(this, token, reason);
    OfflineReconnectNotification.clear(this);
    localGeneration();
    publishLocal();
    Diagnostics.record(this, "离线连接提示结束", reason);
  }

  private void connectOffline(JSONObject sample, String promptToken) {
    localPolicy.begin();
    if (!publishLocal()) {
      localPolicy.finish(false, "无法保存消费记录");
      endPrompt("无法保存消费记录");
      publishLocal();
      return;
    }
    localGeneration();
    Diagnostics.event(this, "phone_local_begin",
        promptToken == null ? "Mac 失联后手机新起播 · 自动接管" : "用户确认连接耳机",
        Diagnostics.fields("event", sample.optInt("event")), true);
    long budget = promptToken == null ? OfflineReconnectFlow.CONNECT_MS
        : Math.max(0, promptFlow.deadline() - SystemClock.elapsedRealtime());
    headphoneControl.connectLocal(sample, promptToken, budget, (success, message) -> {
      localPolicy.finish(success, message);
      if (promptToken != null) {
        endPrompt(message);
        if (!stopped && HeadphoneControl.ready(this)) {
          try { OfflineReconnectNotification.result(this, success); }
          catch (Exception ignored) { }
        }
      }
      localGeneration();
      publishLocal();
      if (!stopped) observer.refreshForHandoff();
      HeadphoneControl.message(this, localPolicy.status());
      requestFastPing();
    });
    requestFastPing();
  }

  private final Runnable localTick =
      new Runnable() {
        public void run() {
          if (stopped) return;
          evaluateLocal();
          main.postDelayed(this, 1000);
        }
      };

  // Worker-only: suspend polling, keep the authorized playback observer and OS discovery entry
  // points.
  private void pauseNetwork(String reason) {
    if (events != null) { events.close(); events = null; }
    networkPaused = true;
    if (heartbeatTask != null) heartbeatTask.cancel(false);
    Diagnostics.record(this, "网络重试暂停", reason + "；本地播放观察独立运行");
    main.post(this::evaluateLocal);
  }

  private void resumeNetwork() {
    if (!networkPaused || stopped) return;
    networkPaused = false;
    failedAt = 0;
    nextAttempt = 0;
    failures = 0;
    heartbeatTask = worker.scheduleWithFixedDelay(this::diagnosticTick, 3, 3, TimeUnit.SECONDS);
  }

  private HeadphoneControl headphoneControl;
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile boolean stopped;
  private Link.Client client;
  private volatile long lastAck;
  private long failedAt, nextAttempt, lastDiscovery;
  private int count, failures;
  private String displayed = "";
  private BleDiscovery discovery;
  private ConnectivityManager.NetworkCallback network;
  private volatile int routeGeneration;
  private final String serviceID = java.util.UUID.randomUUID().toString();
  private String stopReason = "系统回调或未知原因";
  private long workerEndElapsed, workerEndUptime, mainElapsed, mainUptime;
  private int pulseCount;
  private final java.util.concurrent.atomic.AtomicBoolean fastQueued =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile long lastFastStarted;

  // The same serial executor owns all requests, so urgent events cannot race authentication.
  private void requestFastPing() {
    ScheduledExecutorService current = worker;
    if (stopped
        || networkPaused
        || current == null
        || current.isShutdown()
        || !fastQueued.compareAndSet(false, true)) return;
    long delay = Math.max(0, 100 - (SystemClock.elapsedRealtime() - lastFastStarted));
    try {
      current.schedule(
          () -> {
            fastQueued.set(false); // A change during this request can queue one follow-up.
            if (stopped) return;
            lastFastStarted = SystemClock.elapsedRealtime();
            Diagnostics.event(
                this, "event_ping", "状态变化立即上报", Diagnostics.fields("service", serviceID), false);
            tick();
          },
          delay,
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      fastQueued.set(false);
    }
  }

  private final Runnable diagnosticPulse =
      new Runnable() {
        public void run() {
          if (stopped || worker == null) return;
          long elapsed = SystemClock.elapsedRealtime(), uptime = SystemClock.uptimeMillis();
          if (mainElapsed > 0)
            Diagnostics.gap(
                ConnectionService.this, "主线程", elapsed - mainElapsed, uptime - mainUptime, 5000);
          mainElapsed = elapsed;
          mainUptime = uptime;
          if (++pulseCount % 6 == 0)
            Diagnostics.event(
                ConnectionService.this,
                "main_pulse",
                "主线程运行中",
                Diagnostics.fields(
                    "service",
                    serviceID,
                    "power",
                    Diagnostics.power(),
                    "network",
                    Diagnostics.network()),
                false);
          main.postDelayed(this, 5000);
        }
      };

  private void diagnosticTick() {
    long elapsed = SystemClock.elapsedRealtime(), uptime = SystemClock.uptimeMillis();
    if (workerEndElapsed > 0)
      Diagnostics.gap(this, "心跳线程", elapsed - workerEndElapsed, uptime - workerEndUptime, 3000);
    Diagnostics.event(
        this,
        "worker_tick",
        "心跳任务执行",
        Diagnostics.fields(
            "service",
            serviceID,
            "retry_wait_ms",
            Math.max(0, nextAttempt - elapsed),
            "last_ack_age_ms",
            lastAck == 0 ? -1 : elapsed - lastAck),
        false);
    try {
      tick();
    } finally {
      workerEndElapsed = SystemClock.elapsedRealtime();
      workerEndUptime = SystemClock.uptimeMillis();
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    observer = new PhoneObservation(this, this::observationChanged);
    headphoneControl = new HeadphoneControl(this, this::requestFastPing, observer);
    OfflineReconnectNotification.createChannel(this);
    IntentFilter promptFilter = new IntentFilter();
    promptFilter.addAction(OfflineReconnectNotification.CONNECT);
    promptFilter.addAction(OfflineReconnectNotification.IGNORE);
    promptFilter.addDataScheme("soundlink");
    registerReceiver(promptActions, promptFilter, Context.RECEIVER_NOT_EXPORTED);
    Link.prefs(this).registerOnSharedPreferenceChangeListener(policyChanges);
    Diagnostics.service(this, serviceID, "create", "新服务实例");
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "connection",
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW));
    network =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(Network n) {
            Diagnostics.event(
                ConnectionService.this,
                "network_available",
                "默认网络可用回调",
                Diagnostics.fields("network", n.toString(), "state", Diagnostics.network()),
                true);
            main.post(
                () -> {
                  if (worker != null
                      && !stopped
                      && lastAck > 0
                      && SystemClock.elapsedRealtime() - lastAck > 10000) {
                    worker.execute(
                        () -> {
                          resumeNetwork();
                          nextAttempt = 0;
                          tick();
                        });
                    discover(null);
                  }
                });
          }

          @Override
          public void onLost(Network n) {
            Diagnostics.event(
                ConnectionService.this,
                "network_lost",
                "默认网络丢失回调",
                Diagnostics.fields("network", n.toString(), "state", Diagnostics.network()),
                true);
          }

          @Override
          public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            Diagnostics.event(
                ConnectionService.this,
                "network_capabilities",
                "默认网络能力变化",
                Diagnostics.fields("network", n.toString(), "state", Diagnostics.network()),
                false);
          }
        };
    getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(network);
  }

  private Notification notification(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, ConnectionService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, "connection")
        .setSmallIcon(R.drawable.ic_soundlink)
        .setContentTitle("Sound Link")
        .setContentText(
            getString(
                lastAck > 0 && SystemClock.elapsedRealtime() - lastAck < 10000
                    ? R.string.notification_connected
                    : networkPaused
                        ? R.string.notification_offline
                        : R.string.notification_running))
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(
            new Notification.Action.Builder(null, getString(R.string.notification_pause), stop)
                .build())
        .build();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "" : intent.getAction();
    Diagnostics.service(
        this,
        serviceID,
        "start_intent",
        String.valueOf(action) + " · " + (intent == null ? "系统" : intent.getStringExtra("reason")));

    if ("STOP".equals(action)) {
      stopReason = "用户暂停";
      Recovery.disable(this);
      return START_NOT_STICKY;
    }
    if (!Link.prefs(this).contains("peer") || "WAKE".equals(action) && !Recovery.enabled(this)) {
      stopReason = "未配对或自动恢复已关闭";
      stopSelf();
      return START_NOT_STICKY;
    }
    if (worker == null) {
      startForeground(
          1, notification("正在连接 Mac"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      stopped = false;
      failedAt = SystemClock.elapsedRealtime();
      Link.prefs(this)
          .edit()
          .putBoolean("running", true)
          .putLong("ack_elapsed", 0)
          .putInt("sent", 0)
          .putInt("received", 0)
          .putInt(
              "ack_boot",
              Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0))
          .apply();
      bindLocalPolicy();
      worker = Executors.newSingleThreadScheduledExecutor();
      heartbeatTask = worker.scheduleWithFixedDelay(this::diagnosticTick, 0, 3, TimeUnit.SECONDS);
      main.post(localTick);
      main.post(diagnosticPulse);
      Diagnostics.record(
          this,
          "连接服务启动",
          intent == null
              ? "系统"
              : intent.getStringExtra("reason") == null ? "用户连接" : intent.getStringExtra("reason"));
    }
    BluetoothDevice device =
        intent == null ? null : intent.getParcelableExtra("device", BluetoothDevice.class);
    if (("START".equals(action) || (intent != null && action == null)) && networkPaused)
      worker.execute(
          () -> {
            resumeNetwork();
            tick();
          });
    if ("DISCOVER".equals(action)) discover(device);
    else if ("WAKE".equals(action)
        && (lastAck == 0 || SystemClock.elapsedRealtime() - lastAck > 10000)) discover(device);
    return START_NOT_STICKY;
  }

  private void state(String value) {
    if (stopped) return;
    Link.prefs(this)
        .edit()
        .putString("state", value)
        .putLong("service_tick", SystemClock.elapsedRealtime())
        .apply();
    if (!displayed.equals(value)) {
      displayed = value;
      getSystemService(NotificationManager.class).notify(1, notification(value));
    }
  }

  private void refreshObservation(boolean resync) throws Exception {
    FutureTask<Void> sample =
        new FutureTask<>(
            () -> {
              if (stopped) throw new IllegalStateException("服务已结束");
              observer.refreshForConnection(resync);
              return null;
            });
    main.post(sample);
    try {
      sample.get(1500, TimeUnit.MILLISECONDS);
    } catch (Exception error) {
      sample.cancel(false);
      throw error;
    }
  }

  private void tick() {
    if (stopped || networkPaused) return;
    long now = SystemClock.elapsedRealtime();
    // Heartbeat freshness is not the lifetime of the read-only playback observer.
    if (now < nextAttempt) return;
    try {
      if (client == null)
        client = new Link.Client(new JSONObject(Link.prefs(this).getString("peer", "")));
      boolean resync =
          lastAck == 0
              || SystemClock.elapsedRealtime() - lastAck >= 10000
              || client.needsStateSync();
      refreshObservation(resync);
      long start = SystemClock.elapsedRealtime();
      String pairingGeneration = PairingLifecycle.generation(this);
      JSONObject result = client.ping(this, "scheduled", resync);
      long latency = SystemClock.elapsedRealtime() - start;
      if (stopped || !Link.prefs(this).contains("peer") || !pairingGeneration.equals(PairingLifecycle.generation(this))) return;
      if (latency > 8000) throw new java.io.IOException("心跳响应超时");
      recordAck(result, latency, pairingGeneration);
      failures = 0;
      failedAt = 0;
      nextAttempt = 0;
    } catch (Exception e) {
      if (stopped) return;
      if (e instanceof Link.Client.RemoteFailure remote && "MAC_UNAVAILABLE".equals(remote.code)) {
        Diagnostics.record(this, "Mac 不可用", "暂停网络轮询，保留已授权的本地播放观察");
        pauseNetwork("Mac 明确不可用");
        return;
      }
      now = SystemClock.elapsedRealtime();
      if (failedAt == 0) failedAt = now;
      failures++;
      state("离线 · " + friendly(e));
      if (failures == 1) Diagnostics.record(this, "连接中断", DiagnosticTiming.failure(e));
      nextAttempt = now + Math.min(30000, 3000L * (1L << Math.min(3, failures - 1)));
      Diagnostics.event(
          this,
          "heartbeat_failure",
          "本轮心跳失败 · " + diagnosticsFailureName(e),
          Diagnostics.fields(
              "exception",
              e.getClass().getSimpleName(),
              "failures",
              failures,
              "retry_in_ms",
              nextAttempt - now,
              "last_ack_age_ms",
              lastAck == 0 ? -1 : now - lastAck,
              "network",
              Diagnostics.network(),
              "power",
              Diagnostics.power()),
          true);
      if (Recovery.permissions(this) && now - lastDiscovery > 20000) {
        lastDiscovery = now;
        main.post(() -> discover(null));
      }
      if (now - failedAt > 75000) {
        pauseNetwork("连续失败超过 75 秒");
      }
    }
  }

  private void recordAck(JSONObject result, long latency, String pairingGeneration) throws Exception {
    synchronized (PairingLifecycle.class) {
      if (!pairingGeneration.equals(PairingLifecycle.generation(this)) || !Link.prefs(this).contains("peer")) return;
      if (events == null && result.optBoolean("supports_events")) events = new PairingLifecycle.Events(this);
      if (!result.optString("pairing_id").isEmpty()) Link.prefs(this).edit().putString("pairing_id", result.getString("pairing_id")).apply();
      long gap = lastAck == 0 ? -1 : SystemClock.elapsedRealtime() - lastAck;
      boolean restored = lastAck == 0 || gap > 10000;
      Diagnostics.heartbeat(this, restored, gap, latency);
      lastAck = SystemClock.elapsedRealtime();
      count++;
      Link.prefs(this)
          .edit()
          .putInt("sent", count)
          .putInt("received", count)
          .putInt("mac_count", result.getInt("count"))
          .putLong("ack_elapsed", lastAck)
          .putLong("ack_wall", System.currentTimeMillis())
          .putLong("latency", latency)
          .apply();
      if (restored) {
        int reconnects = Link.prefs(this).getInt("connections", 0) + 1;
        Link.prefs(this).edit().putInt("connections", reconnects).apply();
      }
    }
    JSONObject observation = result.optJSONObject("observation");
    if (observation != null) {
      if (!HeadphoneSelection.receive(this, observation, pairingGeneration)) return;
      main.post(
          () -> {
            if (!stopped) observer.start();
          });
    } else main.post(() -> observer.stop());
    long receivedAt = SystemClock.elapsedRealtime();
    JSONObject handoff = result.optJSONObject("handoff");
    main.post(
        () -> {
          if (!stopped) {
            bindLocalPolicy();
            if (promptFlow.waiting()) endPrompt("电脑连接已恢复");
            JSONObject local = PhoneObservation.wire(this);
            if (observation != null && local != null) {
              localPolicy.remote(
                  observation,
                  handoff != null && handoff.optBoolean("enabled"),
                  local,
                  SystemClock.elapsedRealtime());
              publishLocal();
            }
            headphoneControl.receive(handoff, result.optString("epoch"), latency, receivedAt);
          }
        });
    state("Mac 在线");
  }

  private void discover(BluetoothDevice hint) {
    if (stopped || discovery != null || !Recovery.permissions(this)) return;
    if (networkPaused && SystemClock.elapsedRealtime() - lastDiscovery < 30000) return;
    if (networkPaused) lastDiscovery = SystemClock.elapsedRealtime();
    Diagnostics.event(
        this,
        "gatt_lookup_begin",
        "开始蓝牙地址查找",
        Diagnostics.fields("service", serviceID, "hint", hint != null),
        true);
    try {
      JSONObject peer = new JSONObject(Link.prefs(this).getString("peer", ""));
      String pin = peer.getString("pin");
      int generation = routeGeneration;
      discovery =
          new BleDiscovery(
              this,
              pin,
              new BleDiscovery.Listener() {
                public void state(String text) {
                  Recovery.status(ConnectionService.this, text);
                  Diagnostics.event(
                      ConnectionService.this,
                      "gatt_stage",
                      text,
                      Diagnostics.fields("service", serviceID),
                      false);
                }

                public void failed(String text) {
                  discovery = null;
                  Recovery.status(ConnectionService.this, text);
                  Diagnostics.record(ConnectionService.this, "蓝牙地址查找", text);
                }

                public void resolved(DiscoveryProtocol.Endpoint endpoint) {
                  Diagnostics.event(
                      ConnectionService.this,
                      "gatt_proof_verified",
                      "蓝牙身份与地址签名校验成功",
                      Diagnostics.fields(
                          "service",
                          serviceID,
                          "available",
                          endpoint.available(),
                          "candidate_count",
                          endpoint.hosts().size()),
                      true);
                  discovery = null;
                  if (stopped) return;
                  Link.prefs(ConnectionService.this)
                      .edit()
                      .putLong("gatt_wall", System.currentTimeMillis())
                      .putLong("endpoint_revision", endpoint.revision())
                      .apply();
                  if (!endpoint.available()) {
                    Recovery.status(ConnectionService.this, "已验证 Mac 身份 · 当前不可用");
                    return;
                  }
                  Recovery.status(ConnectionService.this, "地址签名已验证 · 正在验证局域网连接");
                  worker.execute(() -> tryEndpoints(peer, pin, endpoint, generation));
                }
              });
      discovery.start(hint);
    } catch (Exception e) {
      discovery = null;
      Recovery.status(this, "无法查找 Mac · " + friendly(e));
    }
  }

  private void tryEndpoints(
      JSONObject original, String pin, DiscoveryProtocol.Endpoint endpoint, int generation) {
    String pairingGeneration = PairingLifecycle.generation(this);
    Exception failure = null;
    for (String host : endpoint.hosts()) {
      if (stopped || routeGeneration != generation) return;
      try {
        JSONObject candidate =
            new JSONObject(original.toString()).put("host", host).put("port", endpoint.port());
        Link.Client proposed = new Link.Client(candidate);
        refreshObservation(true);
        long start = SystemClock.elapsedRealtime();
        JSONObject response = proposed.ping(this, "gatt_candidate", true);
        long latency = SystemClock.elapsedRealtime() - start;
        synchronized (PairingLifecycle.class) {
          JSONObject current = new JSONObject(Link.prefs(this).getString("peer", ""));
          if (stopped || routeGeneration != generation || !pin.equals(current.getString("pin"))
              || !pairingGeneration.equals(PairingLifecycle.generation(this)))
            return;
          if (latency > 8000) throw new java.io.IOException("新地址回应超时");
          // Persist routing only after both the BLE signature and pinned HTTPS identity succeed.
          Link.prefs(this)
              .edit()
              .putString("peer", candidate.toString())
              .putString("address_source", "蓝牙签名 + TLS 已验证")
              .putLong("address_wall", System.currentTimeMillis())
              .apply();
        }
        client = proposed;
        failures = 0;
        failedAt = 0;
        nextAttempt = 0;
        recordAck(response, latency, pairingGeneration);
        resumeNetwork();
        Recovery.status(this, "蓝牙地址已验证 · 局域网连接已恢复");
        Diagnostics.record(this, "最新地址生效", host + ":" + endpoint.port());
        return;
      } catch (Exception e) {
        failure = e;
      }
    }
    if (!stopped)
      Recovery.status(
          this, "Mac 已在附近，但局域网不可达 · " + (failure == null ? "无可用地址" : friendly(failure)));
  }

  private static String diagnosticsFailureName(Exception e) {
    return e.getMessage() != null && e.getMessage().contains("心跳响应超时")
        ? "端到端耗时超过 8 秒（可能含调度延迟）"
        : DiagnosticTiming.failure(e);
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    Diagnostics.service(this, serviceID, "task_removed", "最近任务移除；不代表服务或进程已退出");
    super.onTaskRemoved(rootIntent);
  }

  static String friendly(Exception e) {
    String text = e.getMessage();
    if (text == null) text = e.getClass().getSimpleName();
    if (text.contains("failed to connect") || text.contains("timed out"))
      return "无法连接 Mac，请检查同一 Wi-Fi 和 Mac 是否打开";
    return text.length() > 130 ? text.substring(0, 130) : text;
  }

  @Override
  public void onDestroy() {
    Diagnostics.service(this, serviceID, "destroy", stopReason);
    stopped = true;
    headphoneControl.close();
    endPrompt("连接服务已停止");
    OfflineReconnectNotification.clear(this);
    unregisterReceiver(promptActions);
    Link.prefs(this).unregisterOnSharedPreferenceChangeListener(policyChanges);
    localPolicy.stop();
    publishLocal();
    observer.stop();
    routeGeneration++;
    main.removeCallbacksAndMessages(null);
    if (discovery != null) {
      discovery.close();
      discovery = null;
    }
    if (events != null) events.close();
    if (worker != null) worker.shutdownNow();
    if (network != null)
      getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
    Link.prefs(this)
        .edit()
        .putBoolean("running", false)
        .putLong("ack_elapsed", 0)
        .putString("state", Recovery.enabled(this) ? "等待 Mac 自动恢复" : "连接已停止")
        .apply();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
