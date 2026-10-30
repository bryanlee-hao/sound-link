package app.soundlink.android;

import java.util.Locale;
import org.json.JSONObject;

/** Only an exact Bluetooth identity is safe to accept without a user decision. */
final class HeadphoneMatch {
  static String address(String value) {
    String normalized = value.toUpperCase(Locale.ROOT).replace('-', ':');
    return normalized.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") ? normalized : "";
  }

  static boolean automatic(JSONObject target, String candidate) {
    String address = address(candidate);
    String previous = target.optString("previousAddress");
    return "phone".equals(target.optString("pendingSide"))
        && !address.isEmpty()
        && address.equals(address(target.optString("macAddress")))
        && (previous.isEmpty() || address.equals(address(previous)));
  }

  static boolean acceptsSnapshot(JSONObject current, JSONObject incoming) {
    return incoming.has("revision") && incoming.optInt("revision", -1) >= current.optInt("revision", -1);
  }
}
