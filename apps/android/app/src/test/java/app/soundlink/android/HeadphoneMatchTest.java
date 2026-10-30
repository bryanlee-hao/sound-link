package app.soundlink.android;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeadphoneMatchTest {
  private JSONObject proposal() throws Exception {
    return new JSONObject().put("pendingSide", "phone").put("revision", 7)
        .put("macAddress", "AA:BB:CC:DD:EE:FF").put("macName", "Same name");
  }
  @Test public void acceptsExactIdentityWithNoPreviousSelection() throws Exception {
    assertTrue(HeadphoneMatch.automatic(proposal(), "aa-bb-cc-dd-ee-ff"));
  }
  @Test public void neverMatchesByNameOrMalformedAddress() throws Exception {
    assertFalse(HeadphoneMatch.automatic(proposal(), "11:22:33:44:55:66"));
    assertFalse(HeadphoneMatch.automatic(proposal(), "Same name"));
    assertFalse(HeadphoneMatch.automatic(new JSONObject(), ""));
  }
  @Test public void previousDifferentHeadphonesRequireConfirmation() throws Exception {
    JSONObject target = proposal().put("previousAddress", "11:22:33:44:55:66");
    assertFalse(HeadphoneMatch.automatic(target, "AA:BB:CC:DD:EE:FF"));
    target.put("previousAddress", "AA:BB:CC:DD:EE:FF");
    assertTrue(HeadphoneMatch.automatic(target, "AA:BB:CC:DD:EE:FF"));
  }
  @Test public void oppositeSideOrCompletedProposalCannotBeAutoConfirmed() throws Exception {
    assertFalse(HeadphoneMatch.automatic(proposal().put("pendingSide", "mac"), "AA:BB:CC:DD:EE:FF"));
    JSONObject target = proposal(); target.remove("pendingSide");
    assertFalse(HeadphoneMatch.automatic(target, "AA:BB:CC:DD:EE:FF"));
  }
  @Test public void oldHeartbeatCannotRollBackNewerSelection() throws Exception {
    JSONObject current = new JSONObject().put("revision", 8);
    assertFalse(HeadphoneMatch.acceptsSnapshot(current, proposal()));
    assertTrue(HeadphoneMatch.acceptsSnapshot(current, proposal().put("revision", 8)));
    assertTrue(HeadphoneMatch.acceptsSnapshot(current, proposal().put("revision", 9)));
    assertFalse(HeadphoneMatch.acceptsSnapshot(current, new JSONObject()));
  }
}
