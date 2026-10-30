package app.soundlink.android;

import android.content.Context;

/** Keep raw errors in diagnostics; translate actionable errors at the presentation boundary. */
final class UserMessage {
  static String error(Context context, Exception error) {
    String raw = ConnectionService.friendly(error);
    if (context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh"))
      return raw;
    if (!raw.matches("(?s).*[\\p{IsHan}].*")) return raw;
    Diagnostics.record(context, "ui_error", raw);
    if (raw.contains("权限")) return "Allow the required permissions in Settings, then try again.";
    if (raw.contains("过期"))
      return "The pairing code or session expired. Reconnect or scan a new code.";
    if (raw.contains("身份") || raw.contains("证书") || raw.contains("签名"))
      return "Device verification failed. Check the paired Mac and scan its code again.";
    if (raw.contains("目标") || raw.contains("耳机"))
      return "Check your headphone selection on both devices and try again.";
    return "Couldn’t connect. Check your Mac and local network, then try again. Details are in"
               + " Diagnostics.";
  }
}
