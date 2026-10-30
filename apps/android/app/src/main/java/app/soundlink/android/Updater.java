package app.soundlink.android;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class Updater {
  private static final String BASE = BuildConfig.UPDATE_BASE_URL;

  static boolean enabled() { return !BASE.isEmpty(); }

  static java.net.URL checkedURL(String address) throws Exception {
    return checkedURL(address, BASE);
  }

  static java.net.URL checkedURL(String address, String base) throws Exception {
    if (base.isEmpty()) throw new IOException("Updates are not configured");
    java.net.URL root = new java.net.URL(base);
    java.net.URL url = new java.net.URL(address);
    if (!"https".equals(root.getProtocol()) || root.getHost().isEmpty()
        || root.getPort() != -1 || root.getUserInfo() != null || root.getQuery() != null || root.getRef() != null
        || !base.endsWith("/") || !"https".equals(url.getProtocol())
        || !root.getHost().equals(url.getHost()) || url.getPort() != -1
        || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
        || !url.getPath().matches(java.util.regex.Pattern.quote(root.getPath())
            + "(release\\.json|Sound-Link-[0-9.]+\\.apk)"))
      throw new IOException("Invalid update URL");
    return url;
  }

  private static HttpsURLConnection open(String address) throws Exception {
    HttpsURLConnection conn = (HttpsURLConnection) checkedURL(address).openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(30000);
    conn.setUseCaches(false);
    return conn;
  }

  static JSONObject latest() throws Exception {
    HttpsURLConnection conn = open(BASE + "release.json");
    try {
      if (conn.getResponseCode() != 200) throw new IOException("Update service unavailable");
      try (InputStream in = conn.getInputStream()) {
        byte[] bytes = in.readNBytes(65537);
        if (bytes.length > 65536) throw new IOException("Invalid update manifest");
        return new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      }
    } finally {
      conn.disconnect();
    }
  }

  static synchronized void download(Context context, JSONObject release) throws Exception {
    long size = release.getLong("size");
    if (size < 1 || size > 100 * 1024 * 1024)
      throw new IOException(context.getString(R.string.message_invalid_package_size));
    File file = new File(context.getCacheDir(), "sound-link-update.apk");
    File partial = new File(context.getCacheDir(), "sound-link-update.partial");
    HttpsURLConnection conn = open(release.getString("url"));
    try {
      if (conn.getResponseCode() != 200)
        throw new IOException(context.getString(R.string.message_download_failed));
      long total = 0;
      try (InputStream in = conn.getInputStream();
          OutputStream out = new FileOutputStream(partial)) {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
          total += n;
          if (total > size)
            throw new IOException(context.getString(R.string.message_download_size_mismatch));
          out.write(buffer, 0, n);
        }
      }
      if (total != size
          || !Link.hash(java.nio.file.Files.readAllBytes(partial.toPath()))
              .equals(release.getString("sha256")))
        throw new IOException(context.getString(R.string.message_package_verification_failed));
      PackageManager pm = context.getPackageManager();
      PackageInfo
          next =
              pm.getPackageArchiveInfo(
                  partial.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES),
          current =
              pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
      if (next == null
          || !context.getPackageName().equals(next.packageName)
          || next.getLongVersionCode() != release.getLong("versionCode")
          || next.getLongVersionCode() <= current.getLongVersionCode())
        throw new IOException(context.getString(R.string.message_invalid_package_identity));
      if (next.signingInfo == null
          || !java.util.Arrays.equals(
              next.signingInfo.getApkContentsSigners(),
              current.signingInfo.getApkContentsSigners()))
        throw new IOException(context.getString(R.string.message_package_signature_changed));
      if (!partial.renameTo(file))
        throw new IOException(context.getString(R.string.message_couldn_t_save_package));
    } finally {
      conn.disconnect();
      partial.delete();
    }
  }

  static void install(Activity activity) {
    activity.startActivity(
        new Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                Uri.parse("content://" + activity.getPackageName() + ".updates/sound-link-update.apk"),
                "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
  }
}
