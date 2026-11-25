package app.soundlink.android;

/** Pure rule for leaving the paused network-retry state. */
final class NetworkRetry {
  /** Wake reason sent when the user brings the app to the foreground. */
  static final String APP_OPEN_REASON = "打开 App 恢复";

  private NetworkRetry() {}

  /**
   * Explicit starts and the user opening the app retry the last known address immediately.
   * Background wakes (companion presence, scan results) keep relying on GATT lookup, so a Mac that
   * is asleep does not cost repeated 75-second retry windows.
   */
  static boolean resumeOnStart(boolean hasIntent, String action, String reason, boolean paused) {
    if (!paused || !hasIntent) return false;
    if (action == null || "START".equals(action)) return true;
    return "WAKE".equals(action) && APP_OPEN_REASON.equals(reason);
  }
}
