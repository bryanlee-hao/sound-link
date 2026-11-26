package app.soundlink.android;

import static org.junit.Assert.*;
import org.junit.Test;

public class MuteLeaseTest {
  @Test public void explicitConnectionTransfersOwnershipWithoutOldTimerAuthority() {
    var lease = new MuteLease();
    lease.begin("waiting", 1000, 5000);
    assertEquals(6000, lease.deadline());
    assertTrue(lease.transfer("waiting", "connecting", 5000, 5000));
    assertEquals("connecting", lease.token());
    assertEquals(10000, lease.deadline());
    assertFalse(lease.canTransfer("waiting", 6000));
    assertFalse(lease.transfer("connecting", "repeat", 6000, 5000));
    assertEquals(10000, lease.deadline());
  }

  @Test public void expirationOrUserReleaseCannotBeRemutedByTransfer() {
    var lease = new MuteLease();
    lease.begin("waiting", 1000, 5000);
    assertFalse(lease.transfer("waiting", "connecting", 6000, 5000));
    lease.clear();
    assertFalse(lease.transfer("waiting", "connecting", 6001, 5000));
    assertFalse(lease.active());
  }

  @Test public void wrongTokenOrExcessiveBudgetDoesNotExtendMute() {
    var lease = new MuteLease();
    lease.begin("waiting", 0, 5000);
    assertFalse(lease.transfer("old", "connecting", 4000, 5000));
    assertFalse(lease.transfer("waiting", "connecting", 4000, 5001));
    assertEquals(5000, lease.deadline());
    assertThrows(IllegalStateException.class, () -> lease.begin("repeat", 4000, 5000));
    assertEquals(5000, lease.deadline());
    assertTrue(lease.transfer("waiting", "connecting", 4999, 5000));
    assertTrue(lease.deadline() < 10000);
  }

  @Test public void provisionalPreMuteIsClaimedOnceByTheHandoff() {
    var lease = new MuteLease();
    lease.beginProvisional("premute", 1000, 3000);
    assertTrue(lease.provisional());
    assertTrue(lease.transfer("premute", "connect", 2500, 5000));
    assertFalse(lease.provisional());
    assertEquals(7500, lease.deadline());
    assertFalse(lease.transfer("connect", "again", 3000, 5000));
    lease.clear();
    assertFalse(lease.provisional());
  }

  @Test public void ordinaryMuteIsNeverProvisional() {
    var lease = new MuteLease();
    lease.begin("connect", 0, 5000);
    assertFalse(lease.provisional());
  }
}
