package app.soundlink.android;

import static org.junit.Assert.*;

import javax.net.ssl.SSLHandshakeException;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in integration check against a running Mac; never requests pairing or changes its data. */
public class LiveTLSVerificationTest {
  JSONObject config() throws Exception {
    String pin = System.getenv("SOUNDLINK_TEST_PIN");
    Assume.assumeTrue(pin != null);
    return new JSONObject()
        .put("id", pin.substring(0, 32))
        .put("pin", pin)
        .put("host", System.getenv("SOUNDLINK_TEST_HOST"))
        .put("port", Integer.parseInt(System.getenv("SOUNDLINK_TEST_PORT")));
  }

  @Test
  public void pinnedMacIsAuthenticatedButAnonymousClientIsRejected() throws Exception {
    Link.Client client = new Link.Client(config());
    Exception failure =
        assertThrows(java.io.IOException.class, () -> client.request("/v1/release", null, null));
    assertEquals("连接认证已过期，请重新连接。", failure.getMessage());
  }

  @Test
  public void changedPublicKeyIsRejectedBeforeAPIRequest() throws Exception {
    JSONObject c = config();
    c.put("pin", "b".repeat(64));
    Link.Client client = new Link.Client(c);
    assertThrows(SSLHandshakeException.class, () -> client.request("/v1/release", null, null));
  }

  @Test
  public void changedStableHostnameIsRejected() throws Exception {
    JSONObject c = config();
    c.put("id", "c".repeat(32));
    Link.Client client = new Link.Client(c);
    assertThrows(SSLHandshakeException.class, () -> client.request("/v1/release", null, null));
  }
}
