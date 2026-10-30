package app.soundlink.android;

/** Clock evidence, not an assertion that Android froze a specific process. */
final class DiagnosticTiming {
  record Gap(long elapsedMs, long awakeMs, long sleepMs, long lateAwakeMs, String evidence) {}

  static Gap compare(long elapsed, long uptime, long expected) {
    if (elapsed < 0 || uptime < 0 || uptime > elapsed + 100)
      return new Gap(elapsed, uptime, 0, 0, "clock_reset");
    long sleep = Math.max(0, elapsed - uptime), late = Math.max(0, uptime - expected);
    return new Gap(
        elapsed,
        uptime,
        sleep,
        late,
        sleep >= 2000 && late >= 5000
            ? "sleep_and_late_execution"
            : sleep >= 2000
                ? "device_deep_sleep"
                : late >= 5000 ? "late_execution_unknown_cause" : "on_time");
  }

  static String failure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof javax.net.ssl.SSLException
          || t instanceof java.security.cert.CertificateException) return "tls_or_identity";
      if (t instanceof SecurityException) return "permission";
      if (t instanceof java.net.SocketTimeoutException) return "timeout";
      if (t instanceof java.net.ConnectException) return "connect_failed";
      if (t instanceof java.net.NoRouteToHostException) return "no_route";
      if (t instanceof java.net.UnknownHostException) return "name_resolution";
    }
    return e instanceof java.io.IOException ? "io" : "protocol_or_other";
  }
}
