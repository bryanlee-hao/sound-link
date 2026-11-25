package app.soundlink.android;

import static org.junit.Assert.*;
import org.junit.Test;

public class OfflineReconnectFlowTest {
  @Test public void retiredPromptAndUnknownValuesUseAutoWithoutOverridingNever() {
    for (String value : new String[] {null, "", "future", "prompt", "auto"})
      assertEquals(OfflineReconnectFlow.Mode.AUTO, OfflineReconnectFlow.Mode.parse(value));
    assertEquals(OfflineReconnectFlow.Mode.NEVER, OfflineReconnectFlow.Mode.parse("never"));
  }

  @Test public void storedValuesRoundTrip() {
    for (var mode : OfflineReconnectFlow.Mode.values())
      assertEquals(mode, OfflineReconnectFlow.Mode.parse(mode.value()));
  }
}
