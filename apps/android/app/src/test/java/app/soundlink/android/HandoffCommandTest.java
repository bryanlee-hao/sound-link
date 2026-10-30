package app.soundlink.android;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class HandoffCommandTest {
  @Test public void hiddenMutedPlaybackIsOnlyValidForItsExistingPromptOwner() throws Exception {
    JSONObject sample = new JSONObject("{ageMs:0,playing:'active',audioReadable:false,"
        + "mediaActive:false,musicActive:true,playbackConfigurationsEmpty:true,audioModeNormal:true}");
    assertFalse(HandoffCommand.promptPlayback(sample, false));
    assertTrue(HandoffCommand.promptPlayback(sample, true));
    assertFalse(HandoffCommand.audioReadable(sample));
    assertFalse(HandoffCommand.promptPlayback(new JSONObject(sample.toString()).put("musicActive", false), true));
    assertFalse(HandoffCommand.promptPlayback(new JSONObject(sample.toString()).put("audioModeNormal", false), true));
    assertFalse(HandoffCommand.promptPlayback(new JSONObject(sample.toString()).put("ageMs", 10000), true));
    assertFalse(HandoffCommand.promptPlayback(new JSONObject(sample.toString()).put("playbackConfigurationsEmpty", false), true));
  }

  @Test public void explicitChoiceUsesCurrentPlaybackButStillChecksTargetAndFreshness() throws Exception {
    JSONObject cmd = new JSONObject("{id:'choice',revision:2,address:'AA',instance:'phone',event:1}");
    JSONObject target = new JSONObject("{revision:2,phoneAddress:'AA'}");
    JSONObject sample = new JSONObject("{targetRevision:2,instance:'phone',event:1,media:false,"
        + "mediaActive:true,playing:'active',audioReadable:true,ageMs:0}");
    assertFalse(HandoffCommand.matches(cmd, target, sample, true));
    assertTrue(HandoffCommand.explicitConnectMatches(cmd, target, sample));
    assertFalse(HandoffCommand.explicitConnectMatches(cmd, target, sample.put("ageMs", 10000)));
    sample.put("ageMs", 0).put("playing", "idle");
    assertFalse(HandoffCommand.explicitConnectMatches(cmd, target, sample));
    sample.put("playing", "active").put("targetRevision", 3);
    assertFalse(HandoffCommand.explicitConnectMatches(cmd, target, sample));
    sample.put("targetRevision", 2).put("instance", "new-process");
    assertFalse(HandoffCommand.explicitConnectMatches(cmd, target, sample));
  }

  @Test
  public void delayedResponseOrNewEpochCannotStartHardware() throws Exception {
    JSONObject c = new JSONObject().put("epoch", "session").put("ttlMs", 4000);
    assertEquals(3000, HandoffCommand.budget(c, "session", 750, 250));
    assertEquals(0, HandoffCommand.budget(c, "session", 4001, 0));
    assertEquals(0, HandoffCommand.budget(c, "session", 100, 4000));
    assertEquals(0, HandoffCommand.budget(c, "other", 0, 0));
    c.put("ttlMs", 5000);
    assertEquals(4000, HandoffCommand.budget(c, "session", 750, 250));
    c.put("ttlMs", 5001);
    assertEquals(0, HandoffCommand.budget(c, "session", 0, 0));
    c.put("ttlMs", 15000);
    assertEquals(0, HandoffCommand.budget(c, "session", 0, 0));
    c.put("ttlMs", Long.MAX_VALUE);
    assertEquals(0, HandoffCommand.budget(c, "session", 0, 0));
  }

  @Test
  public void targetEventInstanceAndProtectionAreRechecked() throws Exception {
    JSONObject c =
        new JSONObject(
            "{id:'tx',revision:2,address:'AA:BB:CC:DD:EE:FF',instance:'process',event:7}");
    JSONObject t = new JSONObject("{revision:2,phoneAddress:'AA:BB:CC:DD:EE:FF'}");
    JSONObject s =
        new JSONObject(
            "{targetRevision:2,instance:'process',event:7,protectedAudio:false,media:true,playing:'active'}");
    assertTrue(HandoffCommand.matches(c, t, s, true));
    s.put("event", 8);
    assertFalse(HandoffCommand.matches(c, t, s, true));
    s.put("event", 7).put("protectedAudio", true);
    assertFalse(HandoffCommand.matches(c, t, s, true));
    s.put("protectedAudio", false).put("instance", "new");
    assertFalse(HandoffCommand.matches(c, t, s, true));
    s.put("instance", "process").put("targetRevision", 1);
    assertFalse(HandoffCommand.matches(c, t, s, true));
    s.put("targetRevision", 2).put("playing", "idle");
    assertFalse(HandoffCommand.matches(c, t, s, true));
    assertTrue(HandoffCommand.matches(c, t, s, false));
    t.put("revision", 3);
    assertFalse(HandoffCommand.matches(c, t, s, false));
  }

  @Test
  public void postRequestObservationDoesNotAuthorizeAnotherHardwareAction() throws Exception {
    JSONObject c =
        new JSONObject(
            "{id:'tx',revision:2,address:'AA:BB:CC:DD:EE:FF',instance:'process',event:7}");
    JSONObject t = new JSONObject("{revision:2,phoneAddress:'AA:BB:CC:DD:EE:FF'}");
    JSONObject s =
        new JSONObject(
            "{targetRevision:2,instance:'process',event:8,protectedAudio:true,media:false,playing:'idle'}");
    assertTrue(HandoffCommand.sameTarget(c, t, s));
    assertFalse(HandoffCommand.matches(c, t, s, true));
    assertFalse(HandoffCommand.matches(c, t, s, false));
    s.put("instance", "replacement");
    assertFalse(HandoffCommand.sameTarget(c, t, s));
  }

  @Test
  public void knownDestinationInputAllowsConnectButNeverAuthorizesPausingIt() throws Exception {
    JSONObject c =
        new JSONObject(
            "{id:'tx',revision:2,address:'AA:BB:CC:DD:EE:FF',instance:'process',event:7}");
    JSONObject t = new JSONObject("{revision:2,phoneAddress:'AA:BB:CC:DD:EE:FF'}");
    JSONObject s =
        new JSONObject(
            "{targetRevision:2,instance:'process',event:7,protectedAudio:true,audioReadable:true,media:true,playing:'active'}");
    assertTrue(HandoffCommand.matches(c, t, s, true));
    assertFalse(HandoffCommand.matches(c, t, s, false));
    s.put("audioReadable", false).put("protectedAudio", false);
    assertFalse(HandoffCommand.matches(c, t, s, true));
    assertFalse(HandoffCommand.matches(c, t, s, false));
    s.put("audioReadable", true).put("playing", "unknown");
    assertFalse(HandoffCommand.matches(c, t, s, true));
    s.put("playing", "active").put("media", false);
    assertFalse(HandoffCommand.matches(c, t, s, true));
  }
}
