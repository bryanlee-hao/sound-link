package app.soundlink.android;

import static org.junit.Assert.*;
import org.junit.Test;

public class NetworkRetryTest {
  @Test public void appOpenWakeResumesPausedNetwork() {
    assertTrue(NetworkRetry.resumeOnStart(true, "WAKE", NetworkRetry.APP_OPEN_REASON, true));
  }

  @Test public void explicitStartsResumePausedNetwork() {
    assertTrue(NetworkRetry.resumeOnStart(true, "START", "用户连接", true));
    assertTrue(NetworkRetry.resumeOnStart(true, null, null, true));
  }

  @Test public void backgroundWakesKeepNetworkPaused() {
    assertFalse(NetworkRetry.resumeOnStart(true, "WAKE", "伴生设备在场", true));
    assertFalse(NetworkRetry.resumeOnStart(true, "WAKE", "系统蓝牙广播", true));
    assertFalse(NetworkRetry.resumeOnStart(true, "DISCOVER", "用户查找最新地址", true));
  }

  @Test public void nothingToResumeWhenNotPausedOrSystemRestart() {
    assertFalse(NetworkRetry.resumeOnStart(true, "WAKE", NetworkRetry.APP_OPEN_REASON, false));
    assertFalse(NetworkRetry.resumeOnStart(false, null, null, true));
  }
}
