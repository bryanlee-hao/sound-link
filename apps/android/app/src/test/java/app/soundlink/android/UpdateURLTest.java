package app.soundlink.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateURLTest {
  private static final String BASE = "https://updates.example.test/sound-link/downloads/";
  private static java.net.URL check(String url) throws Exception { return Updater.checkedURL(url, BASE); }
  @Test public void unconfiguredBuildCannotUpdate() {
    assertThrows(Exception.class, () -> Updater.checkedURL(BASE + "release.json", ""));
  }
  @Test public void acceptsOnlyCloudManifestAndVersionedApk() throws Exception {
    assertEquals("updates.example.test", check("https://updates.example.test/sound-link/downloads/release.json").getHost());
    assertEquals("https", check("https://updates.example.test/sound-link/downloads/Sound-Link-0.5.1.apk").getProtocol());
    for (String url : new String[] {
        "https://updates.example.test/downloads/sound-link/release.json",
        "https://other.example.test/sound-link/downloads/release.json",
        "http://updates.example.test/sound-link/downloads/release.json",
        "https://evil.example/sound-link/downloads/release.json",
        "https://updates.example.test/sound-link/downloads/../secret.apk",
        "https://updates.example.test/sound-link/downloads/Sound-Link-0.5.1.apk?x=1",
        "https://user@updates.example.test/sound-link/downloads/release.json",
        "https://updates.example.test:443/sound-link/downloads/release.json"}) {
      assertThrows(Exception.class, () -> check(url));
    }
  }
}
