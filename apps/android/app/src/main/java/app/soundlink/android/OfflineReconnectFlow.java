package app.soundlink.android;

import org.json.JSONObject;

/** Main-thread decision window. UI visibility never owns or extends its deadline. */
final class OfflineReconnectFlow {
  static final String PREFERENCE = "offline_reconnect_mode";
  // Keep the prompt implementation for later; current releases use automatic reconnection.
  static final boolean PROMPT_ENABLED = false;
  static final long WAIT_MS = 5000, CONNECT_MS = 5000;

  enum Mode {
    NEVER, PROMPT, AUTO;

    static Mode parse(String value) {
      if ("never".equals(value)) return NEVER;
      if ("prompt".equals(value) && PROMPT_ENABLED) return PROMPT;
      return AUTO;
    }

    boolean available() { return this != PROMPT || PROMPT_ENABLED; }

    String value() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private String token = "", binding = "", instance = "", route = "";
  private long deadline;
  private int revision;
  private boolean connecting;

  boolean active() { return !token.isEmpty(); }
  boolean waiting() { return active() && !connecting; }
  String token() { return token; }
  long deadline() { return deadline; }

  void begin(String id, String targetBinding, JSONObject sample, long now) {
    if (active()) throw new IllegalStateException("A prompt is already active");
    token = id;
    binding = targetBinding;
    instance = sample.optString("instance");
    revision = sample.optInt("targetRevision", -1);
    route = sample.optString("routeKey");
    connecting = false;
    deadline = now + WAIT_MS;
  }

  boolean valid(JSONObject sample, String currentBinding, boolean online, boolean authorized,
      Mode mode, long now) {
    return valid(sample, currentBinding, online, authorized, mode, now, false);
  }

  boolean valid(JSONObject sample, String currentBinding, boolean online, boolean authorized,
      Mode mode, long now, boolean ownsPromptMute) {
    return waiting() && now < deadline && !online && authorized && mode == Mode.PROMPT
        && binding.equals(currentBinding) && sample != null
        && instance.equals(sample.optString("instance"))
        && revision == sample.optInt("targetRevision", -2)
        && HandoffCommand.promptPlayback(sample, ownsPromptMute);
  }

  boolean routeChanged(JSONObject sample) {
    return sample == null || !route.equals(sample.optString("routeKey"));
  }

  boolean routeChanged(String currentRoute) { return !route.equals(currentRoute); }

  boolean accept(String id, long now) {
    if (!waiting() || !token.equals(id) || now >= deadline) return false;
    connecting = true;
    deadline = now + CONNECT_MS;
    return true;
  }

  boolean matches(String id) { return active() && token.equals(id); }

  void clear() {
    token = "";
    connecting = false;
    deadline = 0;
  }
}
