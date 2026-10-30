package app.soundlink.android;

import android.app.*;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.json.*;

final class Diagnostics {
  private static Context context;
  private static final String PROCESS = UUID.randomUUID().toString();
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final AtomicInteger DROPPED = new AtomicInteger();
  private static final AtomicInteger WRITE_ERRORS = new AtomicInteger();
  private static final ThreadPoolExecutor IO =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256));
  private static final ArrayDeque<JSONObject> RECENT = new ArrayDeque<>();
  private static final ConcurrentHashMap<Long, String> REQUESTS = new ConcurrentHashMap<>();
  private static final ThreadLocal<String> FLOW = new ThreadLocal<>();
  private static DiagnosticLogStore store;
  private static JSONObject latestPlayback = new JSONObject(), latestMacReceipt = new JSONObject();
  private static String playbackKey = "", receiptKey = "";
  private static int boot;
  private static volatile String test = "",
      processEvidence = "等待进程证据",
      gapEvidence = "尚未发现执行间隔异常",
      networkEvidence = "尚无请求失败",
      serviceEvidence = "服务尚未启动",
      wakeEvidence = "尚无恢复入口";

  static synchronized void init(Context c) {
    if (context != null) return;
    context = c.getApplicationContext();
    boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    SharedPreferences p = c.getSharedPreferences("soundlink_diagnostics", Context.MODE_PRIVATE);
    int previousPid = p.getInt("pid", -1), previousBoot = p.getInt("boot", -1);
    long previousStart = p.getLong("started", 0);
    String previousProcess = p.getString("process", ""),
        previousVersion = p.getString("version", "");
    test = p.getString("test", "");
    gapEvidence = p.getString("gap_evidence", gapEvidence);
    networkEvidence = p.getString("network_evidence", networkEvidence);
    long started = System.currentTimeMillis();
    p.edit()
        .putString("process", PROCESS)
        .putInt("pid", android.os.Process.myPid())
        .putInt("boot", boot)
        .putLong("started", started)
        .putString("version", BuildConfig.VERSION_NAME)
        .commit();
    processEvidence =
        previousProcess.isEmpty()
            ? "诊断首次启动，无上次进程记录"
            : previousBoot != boot ? "手机重启后启动新进程" : "确认进程已重建；正在查询上次退出原因";
    event(
        c,
        "process_start",
        processEvidence,
        fields(
            "previous_process",
            previousProcess,
            "previous_pid",
            previousPid,
            "previous_boot",
            previousBoot,
            "previous_version",
            previousVersion),
        true);
    submit(
        () -> {
          try {
            List<ApplicationExitInfo> exits =
                c.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
            ApplicationExitInfo match = null;
            for (ApplicationExitInfo info : exits) {
              event(
                  c,
                  "process_exit_history",
                  "系统进程退出记录",
                  fields(
                      "exit_pid",
                      info.getPid(),
                      "exit_wall_ms",
                      info.getTimestamp(),
                      "reason",
                      info.getReason(),
                      "status",
                      info.getStatus(),
                      "importance",
                      info.getImportance()),
                  false);
              if (previousBoot == boot
                  && info.getPid() == previousPid
                  && info.getTimestamp() >= previousStart
                  && info.getTimestamp() <= started) match = info;
            }
            if (!previousProcess.isEmpty() && previousBoot == boot) {
              processEvidence =
                  match == null
                      ? "确认进程重建；系统未提供匹配退出记录，原因未知"
                      : "确认进程重建；系统退出原因："
                          + exitReason(match.getReason())
                          + "（"
                          + match.getReason()
                          + "），状态 "
                          + match.getStatus();
              event(
                  c,
                  "previous_process_result",
                  processEvidence,
                  fields("matched", match != null),
                  true);
            }
          } catch (Exception e) {
            if (!previousProcess.isEmpty() && previousBoot == boot)
              processEvidence = "确认进程重建；系统退出原因查询失败，原因未知";
            event(
                c,
                "exit_history_unavailable",
                "无法查询系统退出原因",
                fields("exception", e.getClass().getSimpleName()),
                true);
          }
        });
    IntentFilter filter = new IntentFilter();
    for (String action :
        new String[] {
          Intent.ACTION_SCREEN_ON,
          Intent.ACTION_SCREEN_OFF,
          Intent.ACTION_USER_PRESENT,
          PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
          PowerManager.ACTION_POWER_SAVE_MODE_CHANGED
        }) filter.addAction(action);
    c.registerReceiver(
        new BroadcastReceiver() {
          public void onReceive(Context ignored, Intent intent) {
            event(context, "power_event", intent.getAction(), fields("power", power()), true);
          }
        },
        filter,
        Context.RECEIVER_NOT_EXPORTED);
  }

  private static String exitReason(int reason) {
    return switch (reason) {
      case ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存回收";
      case ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "崩溃";
      case ApplicationExitInfo.REASON_ANR -> "无响应";
      case ApplicationExitInfo.REASON_USER_REQUESTED -> "用户请求（不等同于划掉任务）";
      case ApplicationExitInfo.REASON_USER_STOPPED -> "用户停止";
      case ApplicationExitInfo.REASON_FREEZER -> "冻结相关终止（不是一般暂停通知）";
      case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用升级";
      case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化";
      case ApplicationExitInfo.REASON_SIGNALED -> "信号终止；仅凭信号无法确认是谁终止";
      case ApplicationExitInfo.REASON_EXIT_SELF -> "进程自行退出";
      default -> "其他/未知";
    };
  }

  static JSONObject fields(Object... pairs) {
    JSONObject object = new JSONObject();
    try {
      for (int i = 0; i + 1 < pairs.length; i += 2)
        object.put(String.valueOf(pairs[i]), pairs[i + 1]);
    } catch (Exception ignored) {
    }
    return object;
  }

  private static void submit(Runnable job) {
    try {
      IO.execute(job);
    } catch (RejectedExecutionException e) {
      DROPPED.incrementAndGet();
    }
  }

  static synchronized void record(Context c, String event, String detail) {
    event(c, "app_event", event + " · " + detail, fields("name", event), true);
  }

  static synchronized void event(
      Context c, String type, String detail, JSONObject data, boolean visible) {
    if (context == null) init(c);
    String safeDetail = detail == null ? "" : detail.replaceAll("[\\r\\n]+", " ");
    if (safeDetail.length() > 350) safeDetail = safeDetail.substring(0, 350);
    JSONObject row =
        fields(
            "v",
            1,
            "seq",
            SEQUENCE.incrementAndGet(),
            "wall_ms",
            System.currentTimeMillis(),
            "elapsed_ms",
            SystemClock.elapsedRealtime(),
            "uptime_ms",
            SystemClock.uptimeMillis(),
            "pid",
            android.os.Process.myPid(),
            "process",
            PROCESS,
            "boot",
            boot,
            "version",
            BuildConfig.VERSION_NAME,
            "test",
            test,
            "thread",
            Thread.currentThread().getName(),
            "type",
            type,
            "detail",
            safeDetail,
            "data",
            data);
    String line = row.toString();
    submit(
        () -> {
          try {
            if (store == null)
              store =
                  new DiagnosticLogStore(
                      new File(context.getFilesDir(), "diagnostics"), 1024 * 1024, 4);
            store.append(line);
          } catch (Exception e) {
            WRITE_ERRORS.incrementAndGet();
          }
        });
    if (visible) {
      String display =
          new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + safeDetail;
      Log.i("SoundLink", display);
      String[] old = Link.prefs(c).getString("events", "").split("\n");
      StringBuilder next = new StringBuilder(display);
      for (int i = 0; i < Math.min(old.length, 11); i++)
        if (!old[i].isEmpty()) next.append('\n').append(old[i]);
      Link.prefs(c).edit().putString("events", next.toString()).apply();
      RECENT.addLast(
          fields(
              "seq",
              row.optLong("seq"),
              "wall_ms",
              row.optLong("wall_ms"),
              "type",
              type,
              "detail",
              safeDetail));
      while (RECENT.size() > 8) RECENT.removeFirst();
    }
  }

  static JSONObject power() {
    JSONObject result = new JSONObject();
    try {
      PowerManager pm = context.getSystemService(PowerManager.class);
      result =
          fields(
              "interactive",
              pm.isInteractive(),
              "locked",
              context.getSystemService(KeyguardManager.class).isDeviceLocked(),
              "idle",
              pm.isDeviceIdleMode(),
              "power_save",
              pm.isPowerSaveMode(),
              "battery_exempt",
              pm.isIgnoringBatteryOptimizations(context.getPackageName()),
              "background_restricted",
              context.getSystemService(ActivityManager.class).isBackgroundRestricted());
      try {
        result.put(
            "standby_bucket",
            context.getSystemService(UsageStatsManager.class).getAppStandbyBucket());
      } catch (Exception ignored) {
      }
    } catch (Exception ignored) {
    }
    return result;
  }

  static JSONObject network() {
    try {
      ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
      Network active = manager.getActiveNetwork();
      NetworkCapabilities caps = manager.getNetworkCapabilities(active);
      return fields(
          "network",
          active == null ? "none" : active.toString(),
          "wifi",
          caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
          "cellular",
          caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
          "vpn",
          caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
          "validated",
          caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
          "restrict_background",
          manager.getRestrictBackgroundStatus());
    } catch (Exception e) {
      return fields("unavailable", true);
    }
  }

  static void wake(Context c, String source, boolean accepted) {
    if (accepted) wakeEvidence = source;
    event(
        c,
        "wake_entry",
        "恢复入口 · " + source + (accepted ? "" : "（忽略/去重）"),
        fields("accepted", accepted, "power", power(), "network", network()),
        accepted);
  }

  static void service(Context c, String id, String stage, String reason) {
    serviceEvidence = stage + " · " + reason;
    event(
        c,
        "service_" + stage,
        "连接服务 · " + serviceEvidence,
        fields("service", id, "reason", reason, "power", power()),
        true);
  }

  static void gap(Context c, String lane, long elapsed, long uptime, long expected) {
    DiagnosticTiming.Gap g = DiagnosticTiming.compare(elapsed, uptime, expected);
    if ("on_time".equals(g.evidence())) return;
    gapEvidence =
        new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date())
            + " · "
            + lane
            + "：间隔 "
            + elapsed
            + " ms；深睡约 "
            + g.sleepMs()
            + " ms；醒着迟到 "
            + g.lateAwakeMs()
            + " ms";
    context
        .getSharedPreferences("soundlink_diagnostics", Context.MODE_PRIVATE)
        .edit()
        .putString("gap_evidence", gapEvidence)
        .apply();
    event(
        c,
        "execution_gap",
        gapEvidence,
        fields(
            "lane",
            lane,
            "evidence",
            g.evidence(),
            "elapsed_delta_ms",
            elapsed,
            "uptime_delta_ms",
            uptime,
            "sleep_ms",
            g.sleepMs(),
            "late_awake_ms",
            g.lateAwakeMs(),
            "expected_ms",
            expected,
            "requests",
            REQUESTS.toString(),
            "power",
            power(),
            "network",
            network()),
        true);
  }

  static void heartbeat(Context c, boolean restored, long gap, long latency) {
    event(
        c,
        "heartbeat_ack",
        restored ? "认证心跳恢复 · 距上次成功 " + gap + " ms（不是进程重启计数）" : "认证心跳有效",
        fields(
            "restored",
            restored,
            "gap_ms",
            gap,
            "latency_ms",
            latency,
            "power",
            power(),
            "network",
            network()),
        restored);
  }

  static synchronized void playback(Context c, JSONObject sample, String source) {
    String key =
        sample.optString("instance")
            + ":"
            + sample.optInt("event")
            + ":"
            + sample.optString("playing")
            + ":"
            + sample.optBoolean("media")
            + ":"
            + sample.optBoolean("protectedAudio");
    if (key.equals(playbackKey)) return;
    playbackKey = key;
    latestPlayback =
        fields(
            "instance",
            sample.optString("instance"),
            "event",
            sample.optInt("event"),
            "playing",
            sample.optString("playing"),
            "media",
            sample.optBoolean("media"),
            "protectedAudio",
            sample.optBoolean("protectedAudio"),
            "playback",
            sample.optString("playback"),
            "connection",
            sample.optString("connection"),
            "route",
            sample.optString("route"),
            "source",
            source,
            "wall_ms",
            System.currentTimeMillis(),
            "elapsed_ms",
            SystemClock.elapsedRealtime(),
            "power",
            power());
    event(
        c,
        "phone_playback_changed",
        "手机播放状态变化 · " + sample.optString("playing") + " · " + source,
        latestPlayback,
        true);
  }

  static synchronized void macReceipt(Context c, JSONObject probe) {
    if (probe == null) return;
    String id = probe.optString("id");
    if (id.isEmpty() || id.length() > 64 || id.equals(receiptKey)) return;
    receiptKey = id;
    latestMacReceipt =
        fields(
            "id",
            id,
            "wall_ms",
            System.currentTimeMillis(),
            "elapsed_ms",
            SystemClock.elapsedRealtime(),
            "power",
            power(),
            "via",
            "https_poll_response");
    event(c, "mac_state_received", "收到 Mac 状态测试标识 · HTTPS 心跳响应（不是主动唤醒）", latestMacReceipt, true);
  }

  static synchronized JSONObject summary() {
    JSONArray events = new JSONArray();
    for (JSONObject row : RECENT) events.put(row);
    return fields(
        "v",
        1,
        "summaryElapsedMs",
        SystemClock.elapsedRealtime(),
        "process",
        PROCESS,
        "pid",
        android.os.Process.myPid(),
        "boot",
        boot,
        "test",
        test,
        "latestPlayback",
        latestPlayback,
        "latestMacReceipt",
        latestMacReceipt,
        "processEvidence",
        processEvidence,
        "gapEvidence",
        gapEvidence,
        "networkEvidence",
        networkEvidence,
        "serviceEvidence",
        serviceEvidence,
        "wakeEvidence",
        wakeEvidence,
        "dropped",
        DROPPED.get(),
        "writeErrors",
        WRITE_ERRORS.get(),
        "recent",
        events);
  }

  static String summaryText() {
    return "进程 "
        + android.os.Process.myPid()
        + " · "
        + PROCESS.substring(0, 8)
        + "\n"
        + processEvidence
        + "\n最近执行间隔："
        + gapEvidence
        + "\n最近请求失败："
        + networkEvidence
        + "\n服务："
        + serviceEvidence
        + "\n最近系统恢复入口："
        + wakeEvidence
        + "\n测试："
        + (test.isEmpty() ? "未标记" : test.substring(0, 8))
        + " · 丢弃 "
        + DROPPED.get()
        + " · 写入错误 "
        + WRITE_ERRORS.get()
        + "\n执行迟到不能单独证明系统冻结；完整证据保存在本机日志。";
  }

  static void mark(Context c, boolean begin) {
    if (begin) {
      gapEvidence = "本轮尚未发现执行间隔异常";
      networkEvidence = "本轮尚无请求失败";
      synchronized (Diagnostics.class) {
        RECENT.clear();
      }
      test = UUID.randomUUID().toString();
      c.getSharedPreferences("soundlink_diagnostics", Context.MODE_PRIVATE)
          .edit()
          .putString("test", test)
          .putString("gap_evidence", gapEvidence)
          .putString("network_evidence", networkEvidence)
          .commit();
    }
    event(
        c,
        begin ? "test_begin" : "test_end",
        begin ? "锁屏测试开始标记" : "锁屏测试结束标记",
        fields("power", power(), "network", network()),
        true);
    if (!begin) {
      test = "";
      c.getSharedPreferences("soundlink_diagnostics", Context.MODE_PRIVATE)
          .edit()
          .remove("test")
          .commit();
    }
  }

  static File export(Context c) throws Exception {
    JSONObject metadata = summary();
    return IO.submit(
            () -> {
              File result = new File(c.getCacheDir(), "sound-link-diagnostics.zip");
              try (ZipOutputStream zip =
                  new ZipOutputStream(
                      new FileOutputStream(
                          new File(c.getCacheDir(), "sound-link-diagnostics.zip.tmp")))) {
                zip.putNextEntry(new ZipEntry("summary.json"));
                zip.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(
                    ("Sound Link "
                            + BuildConfig.VERSION_NAME
                            + " diagnostics\n"
                            + "JSONL timestamps: wall_ms for display, elapsed_ms includes sleep,"
                            + " uptime_ms excludes deep sleep.\n"
                            + "Process change proves recreation, not why it ended. Exit reasons are"
                            + " system reports; missing records do not prove no kill.\n"
                            + "Execution gaps may be scheduling or thread blocking. CONNECT_TLS"
                            + " combines TCP and TLS.\n"
                            + "No tokens, pairing payloads, certificates, SSID, audio or media"
                            + " contents are intentionally logged.\n"
                            + "Files rotate at 1 MiB each, maximum 4 files. Async writes may lose"
                            + " the tail on abrupt termination. dropped/writeErrors are"
                            + " current-process counters.\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (store == null)
                  store =
                      new DiagnosticLogStore(
                          new File(c.getFilesDir(), "diagnostics"), 1024 * 1024, 4);
                for (File file : store.chronologicalFiles()) {
                  zip.putNextEntry(new ZipEntry(file.getName()));
                  try (InputStream in = new FileInputStream(file)) {
                    in.transferTo(zip);
                  }
                  zip.closeEntry();
                }
              }
              java.nio.file.Files.move(
                  new File(c.getCacheDir(), "sound-link-diagnostics.zip.tmp").toPath(),
                  result.toPath(),
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              return result;
            })
        .get(20, TimeUnit.SECONDS);
  }

  static final class Flow {
    final Context c;
    final String source;
    final String id = UUID.randomUUID().toString().substring(0, 8);
    final long elapsed = SystemClock.elapsedRealtime(), uptime = SystemClock.uptimeMillis();

    Flow(Context c, String source) {
      this.c = c;
      this.source = source;
      FLOW.set(id);
      event(
          c,
          "heartbeat_attempt",
          "开始完整认证/心跳尝试",
          fields("flow", id, "source", source, "network", network(), "power", power()),
          false);
    }

    void finish(Throwable error) {
      event(
          c,
          "heartbeat_attempt_end",
          "完整认证/心跳尝试结束",
          fields(
              "flow",
              id,
              "source",
              source,
              "elapsed_ms",
              SystemClock.elapsedRealtime() - elapsed,
              "uptime_ms",
              SystemClock.uptimeMillis() - uptime,
              "success",
              error == null,
              "exception",
              error == null ? "" : error.getClass().getSimpleName()),
          false);
      FLOW.remove();
    }
  }

  static final class Request {
    final Context c;
    final String id = UUID.randomUUID().toString().substring(0, 8);
    final String path;
    final long elapsed = SystemClock.elapsedRealtime(),
        uptime = SystemClock.uptimeMillis(),
        thread = Thread.currentThread().getId();
    String stage = "OPEN";

    Request(Context c, String path) {
      this.c = c;
      this.path = path;
      phase("OPEN");
    }

    void phase(String name) {
      stage = name;
      REQUESTS.put(thread, id + ":" + stage);
      event(
          c,
          "request_phase",
          path + " · " + stage,
          fields(
              "flow",
              FLOW.get(),
              "request",
              id,
              "path",
              path,
              "stage",
              stage,
              "elapsed_ms",
              SystemClock.elapsedRealtime() - elapsed,
              "uptime_ms",
              SystemClock.uptimeMillis() - uptime),
          false);
    }

    void finish(int status, String serverCode, Throwable error) {
      REQUESTS.remove(thread);
      long duration = SystemClock.elapsedRealtime() - elapsed,
          awake = SystemClock.uptimeMillis() - uptime;
      String kind =
          error == null
              ? "success"
              : status >= 400 ? "http_rejected" : DiagnosticTiming.failure(error);
      String detail = path + " · " + stage + " · " + kind + " · " + duration + " ms";
      if (error != null) {
        networkEvidence =
            new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date())
                + " · "
                + detail
                + (status >= 400 ? " · HTTP " + status + " " + serverCode : "");
        c.getSharedPreferences("soundlink_diagnostics", Context.MODE_PRIVATE)
            .edit()
            .putString("network_evidence", networkEvidence)
            .apply();
      }
      List<String> chain = new ArrayList<>();
      for (Throwable t = error; t != null && chain.size() < 5; t = t.getCause())
        chain.add(t.getClass().getSimpleName());
      event(
          c,
          error == null ? "request_success" : "request_failure",
          detail,
          fields(
              "flow",
              FLOW.get(),
              "request",
              id,
              "path",
              path,
              "stage",
              stage,
              "kind",
              kind,
              "status",
              status,
              "server_code",
              serverCode,
              "elapsed_ms",
              duration,
              "uptime_ms",
              awake,
              "sleep_ms",
              Math.max(0, duration - awake),
              "exceptions",
              new JSONArray(chain),
              "network",
              network(),
              "power",
              power()),
          error != null);
    }
  }
}
