package app.soundlink.android;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ConnectionResetTest {
  @Test public void resetWithoutPeerRemovesAbandonedPairingAndGrants() {
    Map<String, Object> preferences = new HashMap<>(Map.of(
        "draft", "old invitation", "pairing_started", 1L,
        "association_id", 7, "association_pin", "old pin",
        "association_request", "pending request", "ble_address", "old address",
        "observe_target", "old headphones", "handoff_pin", "old authorization",
        "phone_local_failure_binding", "old binding", "endpoint_revision", 8));
    preferences.keySet().removeIf(PairingLifecycle::connectionPreference);
    assertTrue(preferences.isEmpty());
  }

  @Test public void resetPreservesIdentityPresentationAndDiagnosticsPreferences() {
    Map<String, Object> retained = Map.of("client_id", "identity", "interface_appearance", "dark",
        "diagnostics_enabled", true, "update_channel", "stable", "offline_reconnect_mode", "never");
    Map<String, Object> preferences = new HashMap<>(retained);
    preferences.put("peer", "paired Mac");
    preferences.put("pairing_id", "session");
    preferences.put("handoff_enabled", true);
    preferences.put("auto_enabled", true);
    preferences.put("offline_reconnect_prompt", "expired choice");
    preferences.put("ack_wall", 123L);
    preferences.keySet().removeIf(PairingLifecycle::connectionPreference);
    assertEquals(retained, preferences);
  }
}
