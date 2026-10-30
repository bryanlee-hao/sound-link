package app.soundlink.android;

import org.json.JSONObject;

/** Main-thread, platform-free policy. An outage is a mode change, never a connect event. */
final class OfflinePhonePolicy {
  private String binding = "", instance = "";
  private int event = -1;
  private boolean enabled, session, macProtected = true;
  private boolean blocked, busy;
  private String reason = "等待可信状态同步";
  private String recoveryToken = java.util.UUID.randomUUID().toString();
  private String macConnection = "unknown";
  private long macAt = -1;

  void bind(String key, boolean previousFailure) {
    if (binding.equals(key)) return;
    binding = key;
    instance = "";
    event = -1;
    enabled = false;
    session = false;
    macProtected = true;
    busy = false;
    blocked = previousFailure;
    reason = previousFailure ? "上次独立接管未完成，等待新的连接确认" : "等待可信状态同步";
    recoveryToken = java.util.UUID.randomUUID().toString();
    macAt = -1;
    macConnection = "unknown";
  }

  void remote(JSONObject remote, boolean switching, JSONObject local, long now) {
    enabled = switching;
    if (!enabled) {
      session = false;
      reason = "Mac 已关闭自动切换";
      return;
    }
    JSONObject mac = remote.optJSONObject("mac");
    if (mac == null
        || mac.optDouble("ageMs", 10000) >= 10000
        || mac.optInt("targetRevision", -1) != local.optInt("targetRevision", -2)) return;
    macAt = now;
    macConnection = mac.optString("connection", "unknown");
    macProtected =
        mac.optBoolean("protectedAudio", true)
            || (mac.has("audioReadable") && !HandoffCommand.audioReadable(mac));
    if ("connected".equals(macConnection)) {
      session = true;
      if (!busy && recoveryToken.equals(remote.optString("phoneRecoveryToken"))) {
        blocked = false;
        reason = "收到新的 Mac 耳机连接确认";
      }
    } else if ("disconnected".equals(macConnection)
        && "disconnected".equals(local.optString("connection"))) session = false;
    // Reconnect establishes a local baseline as well; do not replay an outage's old edge.
    baseline(local);
  }

  private void baseline(JSONObject local) {
    instance = local.optString("instance");
    event = local.optInt("event", -1);
  }

  boolean observe(
      JSONObject local, boolean online, boolean authorized, boolean executorBusy, long now) {
    if (!authorized) {
      enabled = false;
      session = false;
      baseline(local);
      return false;
    }
    if (local.optDouble("ageMs", 10000) >= 10000) return false;
    String nextInstance = local.optString("instance");
    int nextEvent = local.optInt("event", -1);
    boolean newEdge = !instance.isEmpty() && instance.equals(nextInstance) && nextEvent > event;
    baseline(local); // Every edge is consumed once, including blocked/busy/online edges.
    String connection = local.optString("connection", "unknown");
    if (enabled && "connected".equals(connection)) {
      session = true;
      if (!busy) {
        blocked = false;
        reason = "手机已连接目标耳机";
      }
    } else if (!busy
        && "disconnected".equals(connection)
        && macAt >= 0
        && now - macAt < 10000
        && "disconnected".equals(macConnection)) session = false;
    return newEdge
        && !online
        && enabled
        && session
        && !blocked
        && !busy
        && !executorBusy
        && !macProtected
        && HandoffCommand.audioReadable(local)
        && "disconnected".equals(connection)
        && "active".equals(local.optString("playing"))
        && local.optBoolean("media");
  }

  void begin() {
    busy = true;
    blocked = true;
    reason = "手机独立接管中 · 最多 5 秒";
    recoveryToken = java.util.UUID.randomUUID().toString();
  }

  void finish(boolean success, String detail) {
    busy = false;
    blocked = !success;
    if (success) session = true;
    reason = success ? "手机独立接管成功 · 不补发 Mac 暂停" : "独立接管已锁定：" + detail;
    // A pre-failure in-flight response cannot re-arm this session.
    recoveryToken = java.util.UUID.randomUUID().toString();
  }

  void stop() {
    enabled = false;
    session = false;
    reason = "本地观察已停止";
  }

  boolean retainObservation() {
    return enabled && (session || blocked || busy);
  }

  boolean enabled() {
    return enabled;
  }

  boolean allowsPrompt() {
    return enabled && session && !blocked && !busy && !macProtected;
  }

  boolean busy() {
    return busy;
  }

  boolean blocked() {
    return blocked;
  }

  String recoveryToken() {
    return recoveryToken;
  }

  String status() {
    if (busy || blocked) return reason;
    if (!enabled) return reason;
    if (!session) return "暂无使用会话 · 失联时不主动连接";
    if (macProtected) return "最近 Mac 状态受保护 · 暂不独立接管";
    return "使用会话保留 · Mac 失联时等待手机新起播";
  }
}
