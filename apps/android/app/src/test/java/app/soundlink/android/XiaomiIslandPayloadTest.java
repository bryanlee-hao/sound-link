package app.soundlink.android;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class XiaomiIslandPayloadTest {
  private JSONObject data(boolean waiting, long remaining, long wall, boolean first) throws Exception {
    return XiaomiIslandPayload.create("连接 AirPods？", "媒体已临时静音\n5 秒后恢复外放",
        "待连接", waiting, remaining, wall, first, "忽略", "连接").getJSONObject("param_v2");
  }

  @Test public void waitingUsesTwoTextButtonsAndRetainsOrdinaryFallback() throws Exception {
    JSONObject d = data(true, 5000, 100000, true);
    assertFalse(d.getBoolean("filterWhenNoPermission"));
    assertEquals(2, d.getJSONArray("textButton").length());
    assertEquals("忽略", d.getJSONArray("textButton").getJSONObject(0).getString("actionTitle"));
    assertEquals("连接", d.getJSONArray("textButton").getJSONObject(1).getString("actionTitle"));
    assertEquals(XiaomiIslandPayload.IGNORE,
        d.getJSONArray("textButton").getJSONObject(0).getString("action"));
    assertEquals(XiaomiIslandPayload.CONNECT,
        d.getJSONArray("textButton").getJSONObject(1).getString("action"));
    assertFalse(d.getJSONArray("textButton").getJSONObject(1).getBoolean("clickWithCollapse"));
    assertEquals("5 秒后恢复外放", d.getJSONObject("iconTextInfo").getString("subContent"));
    assertFalse(d.toString().contains("actionIntent"));
  }

  @Test public void nativeCountdownProjectsTheExistingDeadlineAndDoesNotRestartOnRefresh() throws Exception {
    JSONObject first = data(true, 5000, 100000, true);
    JSONObject update = data(true, 3900, 101100, false);
    for (JSONObject d : new JSONObject[] {first, update}) {
      JSONObject timer = d.getJSONObject("param_island").getJSONObject("bigIslandArea")
          .getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo");
      assertEquals(-1, timer.getInt("timerType"));
      assertEquals(105000, timer.getLong("timerWhen"));
    }
    assertEquals(4, update.getJSONObject("param_island").getInt("islandTimeout"));
    assertFalse(update.getBoolean("islandFirstFloat"));
    assertFalse(update.getBoolean("enableFloat"));
    assertFalse(update.getJSONObject("param_island").getBoolean("islandOrder"));
    assertEquals("close", update.getString("reopen"));
    assertEquals("reopen", first.getString("reopen"));
  }

  @Test public void connectingRemovesBothActionsAndUsesTheNewBoundedPhase() throws Exception {
    JSONObject d = data(false, 4800, 105000, false);
    assertFalse(d.has("textButton"));
    assertEquals(1, d.getJSONObject("param_island").getInt("islandProperty"));
    assertEquals(109800, d.getJSONObject("param_island").getJSONObject("bigIslandArea")
        .getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo").getLong("timerWhen"));
  }

  @Test public void wallClockChangesDoNotChangeTheRemainingDuration() throws Exception {
    JSONObject timer = data(true, 2000, 500000, false).getJSONObject("param_island")
        .getJSONObject("bigIslandArea").getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo");
    assertEquals(2000, timer.getLong("timerWhen") - timer.getLong("timerSystemCurrent"));
    assertThrows(IllegalArgumentException.class, () -> data(true, 0, 0, false));
    assertThrows(IllegalArgumentException.class, () -> data(true, 5001, 0, true));
  }

  @Test public void missingLabelsCannotReachTheSystemRenderer() {
    assertThrows(IllegalArgumentException.class, () -> XiaomiIslandPayload.create(
        "Title", "Detail", "Waiting", true, 5000, 1000, true, null, "Connect"));
    assertThrows(IllegalArgumentException.class, () -> XiaomiIslandPayload.create(
        "Title", "Detail", "Waiting", true, 5000, 1000, true, "Ignore", " "));
  }
}
