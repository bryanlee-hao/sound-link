package app.soundlink.android;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class DiscoveryProtocolTest {
  JSONObject fixture() throws Exception {
    try (var in = getClass().getResourceAsStream("/discovery-v1.json")) {
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  byte[] bytes(JSONObject f, String key) throws Exception {
    return HexFormat.of().parseHex(f.getString(key));
  }

  @Test
  public void decodesSwiftPacketAndStableServiceUUID() throws Exception {
    JSONObject f = fixture();
    assertEquals(
        f.getString("service_uuid"), DiscoveryProtocol.serviceUUID(f.getString("pin")).toString());
    var endpoint =
        DiscoveryProtocol.verify(bytes(f, "packet"), bytes(f, "nonce"), f.getString("pin"));
    assertTrue(endpoint.available());
    assertEquals(7, endpoint.revision());
    assertEquals(49777, endpoint.port());
    assertEquals(List.of("10.0.0.8", "192.168.8.1"), endpoint.hosts());
  }

  @Test
  public void rejectsReplayWrongPeerAndTampering() throws Exception {
    JSONObject f = fixture();
    byte[] packet = bytes(f, "packet"), nonce = bytes(f, "nonce");
    String pin = f.getString("pin");
    assertThrows(Exception.class, () -> DiscoveryProtocol.verify(packet, new byte[16], pin));
    assertThrows(Exception.class, () -> DiscoveryProtocol.verify(packet, nonce, "a".repeat(64)));
    for (int index : new int[] {0, 1, 10, 26, 91, 93, 94, packet.length - 1}) {
      byte[] changed = packet.clone();
      changed[index] ^= 0x40;
      assertThrows(Exception.class, () -> DiscoveryProtocol.verify(changed, nonce, pin));
    }
  }

  @Test
  public void rejectsTruncatedAndExtendedFrames() throws Exception {
    JSONObject f = fixture();
    byte[] packet = bytes(f, "packet"), nonce = bytes(f, "nonce");
    String pin = f.getString("pin");
    for (int n : new int[] {0, 10, 94, 158, packet.length - 1, packet.length + 1, 512})
      assertThrows(
          Exception.class, () -> DiscoveryProtocol.verify(Arrays.copyOf(packet, n), nonce, pin));
  }
}
