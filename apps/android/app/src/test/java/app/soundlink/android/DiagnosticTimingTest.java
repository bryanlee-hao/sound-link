package app.soundlink.android;

import static org.junit.Assert.*;

import org.junit.Test;

public class DiagnosticTimingTest {
  @Test
  public void detectsDeepSleepWithoutCallingItAProcessKill() {
    var gap = DiagnosticTiming.compare(43000, 3000, 3000);
    assertEquals(40000, gap.sleepMs());
    assertEquals(0, gap.lateAwakeMs());
    assertEquals("device_deep_sleep", gap.evidence());
  }

  @Test
  public void awakeDelayRemainsUnknownAndMixedEvidenceIsPreserved() {
    assertEquals(
        "late_execution_unknown_cause", DiagnosticTiming.compare(18000, 18000, 3000).evidence());
    var gap = DiagnosticTiming.compare(30000, 10000, 3000);
    assertEquals("sleep_and_late_execution", gap.evidence());
    assertEquals(7000, gap.lateAwakeMs());
    assertEquals("on_time", DiagnosticTiming.compare(3002, 3000, 3000).evidence());
    assertEquals("clock_reset", DiagnosticTiming.compare(-1, 2000, 3000).evidence());
  }

  @Test
  public void classifiesNetworkCausesWithoutLoggingTheirMessages() {
    assertEquals(
        "timeout",
        DiagnosticTiming.failure(
            new java.io.IOException(new java.net.SocketTimeoutException("secret"))));
    assertEquals(
        "tls_or_identity",
        DiagnosticTiming.failure(new javax.net.ssl.SSLHandshakeException("secret")));
    assertEquals("permission", DiagnosticTiming.failure(new SecurityException("secret")));
    assertEquals(
        "connect_failed", DiagnosticTiming.failure(new java.net.ConnectException("secret")));
    assertEquals(
        "no_route", DiagnosticTiming.failure(new java.net.NoRouteToHostException("secret")));
  }
}
