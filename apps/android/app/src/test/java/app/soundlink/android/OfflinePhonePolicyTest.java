package app.soundlink.android;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class OfflinePhonePolicyTest {
  private JSONObject sample(int event) throws Exception {
    return new JSONObject()
        .put("instance", "phone")
        .put("event", event)
        .put("targetRevision", 2)
        .put("ageMs", 0)
        .put("connection", "disconnected")
        .put("playing", "active")
        .put("media", true)
        .put("protectedAudio", false);
  }

  private JSONObject remote(String connection, String token) throws Exception {
    return new JSONObject()
        .put("phoneRecoveryToken", token)
        .put(
            "mac",
            new JSONObject()
                .put("targetRevision", 2)
                .put("ageMs", 0)
                .put("connection", connection)
                .put("protectedAudio", false));
  }

  private OfflinePhonePolicy ready() throws Exception {
    OfflinePhonePolicy p = new OfflinePhonePolicy();
    p.bind("target", false);
    p.remote(remote("connected", p.recoveryToken()), true, sample(1), 100);
    return p;
  }

  @Test
  public void OutageAloneNeverConnectsAndOldSessionHasNoTimeExpiry() throws Exception {
    OfflinePhonePolicy p = ready();
    assertFalse(p.observe(sample(1), false, true, false, 3600000));
    assertTrue(p.observe(sample(2), false, true, false, 3600000));
    p.begin();
    assertFalse(p.observe(sample(3), false, true, false, 3600001));
    assertTrue(p.retainObservation());
  }

  @Test
  public void FailureLocksNewEdgesUntilPostFailureProof() throws Exception {
    OfflinePhonePolicy p = ready();
    String staleToken = p.recoveryToken();
    assertTrue(p.observe(sample(2), false, true, false, 20000));
    p.begin();
    p.finish(false, "timeout");
    assertFalse(p.observe(sample(3), false, true, false, 30000));
    p.remote(remote("connected", staleToken), true, sample(3), 30001);
    assertTrue(p.blocked());
    assertFalse(p.observe(sample(4), false, true, false, 40000));
    p.remote(remote("connected", p.recoveryToken()), true, sample(4), 40001);
    assertFalse(p.blocked());
    assertFalse(p.observe(sample(4), false, true, false, 60000));
    assertTrue(p.observe(sample(5), false, true, false, 60000));
  }

  @Test
  public void LocalConnectionRearmsButBothDisconnectedEndsSession() throws Exception {
    OfflinePhonePolicy p = ready();
    p.begin();
    p.finish(false, "timeout");
    assertFalse(p.observe(sample(2).put("connection", "connected"), false, true, false, 30000));
    assertFalse(p.blocked());
    p.remote(remote("disconnected", p.recoveryToken()), true, sample(2), 30001);
    assertFalse(p.observe(sample(3), false, true, false, 60000));
    assertFalse(p.retainObservation());
  }

  @Test
  public void OnlineProtectedBusyAndUnauthorizedEdgesAreConsumed() throws Exception {
    OfflinePhonePolicy p = ready();
    assertFalse(p.observe(sample(2), true, true, false, 200));
    assertFalse(p.observe(sample(2), false, true, false, 20000));
    assertFalse(p.observe(sample(3).put("protectedAudio", true), false, true, false, 20000));
    assertFalse(p.observe(sample(3), false, true, false, 20000));
    assertFalse(p.observe(sample(4), false, true, true, 20000));
    assertFalse(p.observe(sample(5), false, false, false, 20000));
    assertFalse(p.retainObservation());
  }

  @Test
  public void ColdStartTargetChangeAndDisabledCoordinatorFailClosed() throws Exception {
    OfflinePhonePolicy p = new OfflinePhonePolicy();
    p.bind("target", true);
    assertTrue(p.blocked());
    assertFalse(p.observe(sample(1), false, true, false, 100));
    assertFalse(p.observe(sample(2), false, true, false, 200));
    p = ready();
    p.bind("other", false);
    assertFalse(p.observe(sample(2), false, true, false, 20000));
    p = ready();
    p.remote(remote("connected", p.recoveryToken()), false, sample(2), 20000);
    assertFalse(p.observe(sample(3), false, true, false, 30000));
  }

  @Test
  public void UnknownOrStaleMacEvidenceCannotCreateSession() throws Exception {
    OfflinePhonePolicy p = new OfflinePhonePolicy();
    p.bind("target", false);
    p.remote(remote("unknown", p.recoveryToken()), true, sample(1), 100);
    assertFalse(p.observe(sample(2), false, true, false, 20000));
    JSONObject stale = remote("connected", p.recoveryToken());
    stale.getJSONObject("mac").put("ageMs", 10000);
    p.remote(stale, true, sample(2), 20000);
    assertFalse(p.observe(sample(3), false, true, false, 30000));
  }

  @Test
  public void localInputCanAcquireButUnreadableLocalAndProtectedRemoteCannot() throws Exception {
    OfflinePhonePolicy p = ready();
    assertTrue(
        p.observe(
            sample(2).put("audioReadable", true).put("protectedAudio", true),
            false,
            true,
            false,
            20000));
    assertFalse(p.observe(sample(3).put("audioReadable", false), false, true, false, 20001));
    JSONObject protectedMac = remote("connected", p.recoveryToken());
    protectedMac.getJSONObject("mac").put("protectedAudio", true);
    p.remote(protectedMac, true, sample(3), 20002);
    assertFalse(p.observe(sample(4).put("audioReadable", true), false, true, false, 40000));
    protectedMac.getJSONObject("mac").put("protectedAudio", false).put("audioReadable", false);
    p.remote(protectedMac, true, sample(4), 40001);
    assertFalse(p.observe(sample(5), false, true, false, 60000));
  }
}
