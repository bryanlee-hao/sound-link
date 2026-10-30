package app.soundlink.android;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public class PairingCodeTest {
  JSONObject valid() throws Exception {
    return new JSONObject()
        .put("v", 1)
        .put("id", "a".repeat(32))
        .put("pin", "a".repeat(64))
        .put("name", "Mac")
        .put("host", "192.168.22.1")
        .put("port", 4443)
        .put("invite", "11111111-1111-4111-8111-111111111111")
        .put("secret", "a".repeat(43));
  }

  String qr(JSONObject p) {
    return "soundlink://pair/" + Link.b64(p.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void validInvitationAndAddressRefresh() throws Exception {
    JSONObject p = valid();
    assertEquals("192.168.22.1", Link.code(qr(p)).getString("host"));
    p.remove("secret");
    p.remove("invite");
    p.put("mode", "route");
    assertEquals("route", Link.code(qr(p)).getString("mode"));
  }

  @Test
  public void rejectExternalAndAmbiguousAddresses() throws Exception {
    for (String host :
        new String[] {
          "8.8.8.8",
          "localhost",
          "127.0.0.1",
          "192.168.1.1.evil.test",
          "192.168.1.256",
          "192.168.01.1",
          "10.0.0.1@evil.test",
          "172.32.0.1",
          "::1"
        }) {
      JSONObject p = valid().put("host", host);
      assertThrows(Exception.class, () -> Link.code(qr(p)));
    }
  }

  @Test
  public void rejectChangedIdentityPortAndVersion() throws Exception {
    for (JSONObject p :
        new JSONObject[] {
          valid().put("pin", "b".repeat(64)),
          valid().put("port", 0),
          valid().put("port", 65536),
          valid().put("v", 2),
          valid().put("secret", "short")
        }) assertThrows(Exception.class, () -> Link.code(qr(p)));
    assertThrows(Exception.class, () -> Link.code("https://example.com"));
  }
}
