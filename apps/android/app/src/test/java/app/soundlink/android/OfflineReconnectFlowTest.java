package app.soundlink.android;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class OfflineReconnectFlowTest {
  private JSONObject sample() throws Exception {
    return new JSONObject().put("instance", "phone").put("targetRevision", 2)
        .put("ageMs", 0).put("playing", "active").put("audioReadable", true)
        .put("mediaActive", true).put("routeKey", "2:");
  }

  private OfflineReconnectFlow waiting() throws Exception {
    var flow = new OfflineReconnectFlow();
    flow.begin("choice", "paired-headphones", sample(), 1000);
    return flow;
  }

  @Test public void defaultAndDisabledPromptUseAutoWithoutOverridingNever() {
    assertFalse(OfflineReconnectFlow.PROMPT_ENABLED);
    assertFalse(OfflineReconnectFlow.Mode.PROMPT.available());
    for (String value : new String[] {null, "", "future", "prompt", "auto"})
      assertEquals(OfflineReconnectFlow.Mode.AUTO, OfflineReconnectFlow.Mode.parse(value));
    for (var mode : new OfflineReconnectFlow.Mode[] {OfflineReconnectFlow.Mode.NEVER, OfflineReconnectFlow.Mode.AUTO}) {
      assertTrue(mode.available());
      assertEquals(mode, OfflineReconnectFlow.Mode.parse(mode.value()));
    }
  }

  @Test public void fourthSecondChoiceHasItsOwnDeadlineAndOnlyOneConnect() throws Exception {
    var flow = waiting();
    assertFalse(flow.accept("old-notification", 5000));
    assertEquals(6000, flow.deadline());
    assertTrue(flow.accept("choice", 5000));
    assertFalse(flow.waiting());
    assertEquals(10000, flow.deadline());
    assertFalse(flow.accept("choice", 5500));
    assertFalse(flow.accept("choice", 6000));
    assertEquals(10000, flow.deadline());
  }

  @Test public void expiryAndDismissalCannotRestartAConnection() throws Exception {
    var flow = waiting();
    assertFalse(flow.accept("choice", 6000));
    flow.clear();
    assertFalse(flow.accept("choice", 6001));
    flow.begin("new-choice", "paired-headphones", sample(), 10000);
    assertFalse(flow.accept("choice", 10001));
    assertTrue(flow.accept("new-choice", 10001));
  }

  @Test public void pendingChoiceIsInvalidatedByRecoverySettingsAndAuthorization() throws Exception {
    var flow = waiting();
    var mode = OfflineReconnectFlow.Mode.PROMPT;
    assertTrue(flow.valid(sample(), "paired-headphones", false, true, mode, 2000));
    assertFalse(flow.valid(sample(), "paired-headphones", true, true, mode, 2000));
    assertFalse(flow.valid(sample(), "paired-headphones", false, false, mode, 2000));
    assertFalse(flow.valid(sample(), "new-headphones", false, true, mode, 2000));
    assertFalse(flow.valid(sample(), "paired-headphones", false, true,
        OfflineReconnectFlow.Mode.AUTO, 2000));
    assertFalse(flow.valid(sample(), "paired-headphones", false, true,
        OfflineReconnectFlow.Mode.NEVER, 2000));
    assertFalse(flow.valid(sample(), "paired-headphones", false, true, mode, 6000));
  }

  @Test public void staleStoppedUnknownOrReplacedPlaybackCannotAuthorizeChoice() throws Exception {
    var flow = waiting();
    for (JSONObject invalid : new JSONObject[] {null, sample().put("instance", "new-process"),
        sample().put("targetRevision", 3), sample().put("ageMs", 10000),
        sample().put("audioReadable", false), sample().put("playing", "idle"),
        sample().put("mediaActive", false)}) {
      assertFalse(flow.valid(invalid, "paired-headphones", false, true,
          OfflineReconnectFlow.Mode.PROMPT, 2000));
    }
    // A mute-induced edge reset does not discard the user's current playback intention.
    assertTrue(flow.valid(sample().put("media", false), "paired-headphones", false, true,
        OfflineReconnectFlow.Mode.PROMPT, 2000));
    assertFalse(flow.routeChanged(sample()));
    assertTrue(flow.routeChanged(sample().put("routeKey", "8:other-headphones")));
  }
}
