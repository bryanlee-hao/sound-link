package app.soundlink.android;

/** User preference for what the phone does when the Mac is unreachable and playback starts. */
final class OfflineReconnectFlow {
  static final String PREFERENCE = "offline_reconnect_mode";
  static final long CONNECT_MS = 5000;

  private OfflineReconnectFlow() {}

  enum Mode {
    NEVER, AUTO;

    /** Unknown and retired values (the removed "prompt" mode) fall back to automatic. */
    static Mode parse(String value) {
      return "never".equals(value) ? NEVER : AUTO;
    }

    String value() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }
}
