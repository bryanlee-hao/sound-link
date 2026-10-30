package app.soundlink.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.Base64;
import javax.net.ssl.*;
import org.json.JSONObject;

final class Link {
  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("soundlink", Context.MODE_PRIVATE);
  }

  static String b64(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static byte[] un64(String s) {
    return Base64.getUrlDecoder().decode(s);
  }

  static String hash(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  static JSONObject code(String text) throws Exception {
    if (text.length() > 4096 || !text.startsWith("soundlink://pair/"))
      throw new IOException("请扫描 Mac 上「配对此 Mac」二维码");
    String encoded = text.substring(17);
    if (!encoded.matches("[A-Za-z0-9_-]+")) throw new IOException("二维码格式错误");
    JSONObject p = new JSONObject(new String(un64(encoded), StandardCharsets.UTF_8));
    if (p.getInt("v") != 1
        || !p.getString("id").matches("[0-9a-f]{32}")
        || !p.getString("pin").matches("[0-9a-f]{64}")) throw new IOException("不支持此配对码");
    if (!p.getString("pin").startsWith(p.getString("id"))) throw new IOException("Mac 身份不一致");
    if (!privateIPv4(p.getString("host")) || p.getInt("port") < 1 || p.getInt("port") > 65535)
      throw new IOException("配对地址不是有效的局域网地址");
    if ("route".equals(p.optString("mode"))) return p;
    UUID.fromString(p.getString("invite"));
    if (!p.getString("secret").matches("[A-Za-z0-9_-]{43}") || p.getString("name").length() > 64)
      throw new IOException("配对信息错误");
    return p;
  }

  static boolean privateIPv4(String host) {
    if (!host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
    String[] parts = host.split("\\.");
    int[] n = new int[4];
    try {
      for (int i = 0; i < 4; i++) {
        if (parts[i].length() > 3 || (parts[i].length() > 1 && parts[i].startsWith("0")))
          return false;
        n[i] = Integer.parseInt(parts[i]);
        if (n[i] > 255) return false;
      }
    } catch (Exception e) {
      return false;
    }
    return n[0] == 10 || n[0] == 192 && n[1] == 168 || n[0] == 172 && n[1] >= 16 && n[1] <= 31;
  }

  static synchronized KeyStore keys() throws Exception {
    KeyStore k = KeyStore.getInstance("AndroidKeyStore");
    k.load(null);
    if (!k.containsAlias("soundlink.identity.v1")) {
      KeyPairGenerator g =
          KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
      g.initialize(
          new KeyGenParameterSpec.Builder(
                  "soundlink.identity.v1",
                  KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
              .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
              .setDigests(KeyProperties.DIGEST_SHA256)
              .build());
      g.generateKeyPair();
    }
    return k;
  }

  static String clientID(Context c) {
    SharedPreferences p = prefs(c);
    String id = p.getString("client_id", null);
    if (id == null) {
      id = UUID.randomUUID().toString();
      p.edit().putString("client_id", id).commit();
    }
    return id;
  }

  static JSONObject signed(JSONObject p) throws Exception {
    byte[] raw = p.toString().getBytes(StandardCharsets.UTF_8);
    Signature signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign((PrivateKey) keys().getKey("soundlink.identity.v1", null));
    signature.update("SoundLink/1\n".getBytes(StandardCharsets.UTF_8));
    signature.update(raw);
    return new JSONObject().put("payload", b64(raw)).put("signature", b64(signature.sign()));
  }

  static JSONObject pair(Context c, JSONObject config) throws Exception {
    JSONObject p =
        new JSONObject()
            .put("v", 1)
            .put("action", "pair")
            .put("client_id", clientID(c))
            .put("name", DeviceName.read(c))
            .put(
                "public_key",
                b64(keys().getCertificate("soundlink.identity.v1").getPublicKey().getEncoded()))
            .put("request_id", config.getString("request"))
            .put("invitation_id", config.getString("invite"))
            .put("secret", config.getString("secret"));
    return new Client(config).request("/v1/pair", signed(p), null);
  }

  static final class Client {
    final JSONObject config;
    final SSLSocketFactory factory;
    final String hostname;
    String token, epoch;
    Context diagnosticContext;
    long until;

    Client(JSONObject config) throws Exception {
      this.config = config;
      hostname = config.getString("id") + ".soundlink.local";
      final String pin = config.getString("pin");
      X509TrustManager trust =
          new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] chain, String auth)
                throws java.security.cert.CertificateException {
              throw new java.security.cert.CertificateException("server only");
            }

            public void checkServerTrusted(X509Certificate[] chain, String auth)
                throws java.security.cert.CertificateException {
              try {
                if (chain.length != 1) throw new IOException("Unexpected certificate chain");
                X509Certificate cert = chain[0];
                cert.checkValidity();
                cert.verify(cert.getPublicKey());
                if (!MessageDigest.isEqual(
                    pin.getBytes(StandardCharsets.US_ASCII),
                    hash(cert.getPublicKey().getEncoded()).getBytes(StandardCharsets.US_ASCII)))
                  throw new IOException("Mac 身份已改变，请重新配对");
                if (cert.getExtendedKeyUsage() == null
                    || !cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"))
                  throw new IOException("Invalid server identity");
                boolean found = false;
                Collection<List<?>> names = cert.getSubjectAlternativeNames();
                if (names != null)
                  for (List<?> n : names)
                    if (Integer.valueOf(2).equals(n.get(0)) && hostname.equals(n.get(1)))
                      found = true;
                if (!found) throw new IOException("Mac certificate name mismatch");
              } catch (Exception e) {
                throw new java.security.cert.CertificateException(e);
              }
            }
          };
      SSLContext tls = SSLContext.getInstance("TLSv1.2");
      tls.init(null, new TrustManager[] {trust}, new SecureRandom());
      factory = tls.getSocketFactory();
    }

    HttpsURLConnection open(String path, String bearer) throws Exception {
      URL url = new URL("https://" + config.getString("host") + ":" + config.getInt("port") + path);
      HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
      conn.setSSLSocketFactory(factory);
      // The scanned identity is the authenticated name; the LAN IP is only a route.
      conn.setHostnameVerifier(
          (host, session) -> {
            try {
              X509Certificate cert = (X509Certificate) session.getPeerCertificates()[0];
              if (!config.getString("pin").equals(hash(cert.getPublicKey().getEncoded())))
                return false;
              Collection<List<?>> names = cert.getSubjectAlternativeNames();
              if (names != null)
                for (List<?> n : names)
                  if (Integer.valueOf(2).equals(n.get(0)) && hostname.equals(n.get(1))) return true;
            } catch (Exception ignored) {
            }
            return false;
          });
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(path.equals("/v1/events") ? 25000 : 8000);
      conn.setInstanceFollowRedirects(false);
      conn.setUseCaches(false);
      if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
      return conn;
    }

    static final class RemoteFailure extends IOException {
      final String code;

      RemoteFailure(String code, String message) {
        super(message);
        this.code = code;
      }
    }

    JSONObject request(String path, JSONObject body, String bearer) throws Exception {
      Diagnostics.Request trace =
          diagnosticContext == null ? null : new Diagnostics.Request(diagnosticContext, path);
      HttpsURLConnection conn = null;
      int status = -1;
      String serverCode = "";
      Throwable failure = null;
      try {
        conn = open(path, bearer);
        byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        if (payload != null) {
          conn.setRequestMethod("POST");
          conn.setDoOutput(true);
          conn.setRequestProperty("Content-Type", "application/json");
          conn.setFixedLengthStreamingMode(payload.length);
        }
        if (trace != null) trace.phase("CONNECT_TLS");
        conn.connect();
        if (payload != null) {
          if (trace != null) trace.phase("WRITE_BODY");
          try (OutputStream out = conn.getOutputStream()) {
            out.write(payload);
          }
        }
        if (trace != null) trace.phase("READ_HEADERS");
        status = conn.getResponseCode();
        if (trace != null) trace.phase("READ_BODY");
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new IOException("Mac 返回空响应");
        byte[] raw;
        try (in) {
          raw = in.readNBytes(32769);
        }
        if (trace != null) trace.phase("VERIFY_RESPONSE");
        if (raw.length > 32768) throw new IOException("响应过大");
        JSONObject r = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        String code = r.optString("error");
        serverCode = code.matches("[A-Z0-9_]{1,64}") ? code : "";
        if (status != 200)
          throw new RemoteFailure(serverCode, r.optString("message", r.optString("error", "连接失败")));
        if (!config.getString("id").equals(r.getString("server_id")))
          throw new IOException("Mac 身份不一致");
        return r;
      } catch (Exception e) {
        failure = e;
        throw e;
      } finally {
        if (trace != null) trace.finish(status, serverCode, failure);
        if (conn != null) conn.disconnect();
      }
    }

    void authenticate(Context c) throws Exception {
      diagnosticContext = c.getApplicationContext();
      if (token != null && android.os.SystemClock.elapsedRealtime() < until) return;
      JSONObject challenge =
          request("/v1/challenge", new JSONObject().put("client_id", clientID(c)), null);
      JSONObject response =
          request(
              "/v1/session",
              signed(
                  new JSONObject()
                      .put("v", 1)
                      .put("action", "session")
                      .put("client_id", clientID(c))
                      .put("nonce", challenge.getString("nonce"))),
              null);
      Diagnostics.event(c, "auth_session", "认证会话建立（不代表新进程）", Diagnostics.fields(), false);
      token = response.getString("token");
      epoch = response.getString("epoch");
      until = android.os.SystemClock.elapsedRealtime() + 840000;
    }

    JSONObject selectHeadphone(Context c, JSONObject body) throws Exception {
      authenticate(c);
      return request("/v1/observe/target", body, token);
    }

    JSONObject ping(Context c) throws Exception {
      return ping(c, "scheduled");
    }

    boolean needsStateSync() {
      return token == null || android.os.SystemClock.elapsedRealtime() >= until;
    }

    JSONObject ping(Context c, String source) throws Exception {
      return ping(c, source, false);
    }

    JSONObject ping(Context c, String source, boolean resync) throws Exception {
      Diagnostics.Flow flow = new Diagnostics.Flow(c, source);
      Throwable failure = null;
      try {
        return pingObserved(c, resync);
      } catch (Exception e) {
        failure = e;
        throw e;
      } finally {
        flow.finish(failure);
      }
    }

    private JSONObject pingObserved(Context c, boolean resync) throws Exception {
      boolean stateSync = resync || needsStateSync();
      authenticate(c);
      JSONObject observation = PhoneObservation.wire(c);
      if (observation != null) observation.put("stateSync", stateSync);
      String nonce = UUID.randomUUID().toString();
      try {
        JSONObject r =
            request(
                "/v1/ping",
                new JSONObject()
                    .put("nonce", nonce)
                    .put("device_name", DeviceName.read(c))
                    .put("observation", observation)
                    .put("handoff", HeadphoneControl.wire(c))
                    .put("diagnostics", Diagnostics.summary())
                    .put("recoveryReady", Recovery.enabled(c) && Recovery.association(c) != null),
                token);
        if (!nonce.equals(r.getString("nonce")) || !epoch.equals(r.getString("epoch")))
          throw new IOException("心跳校验失败");
        Diagnostics.macReceipt(c, r.optJSONObject("diagnosticProbe"));
        return r;
      } catch (Exception e) {
        token = null;
        throw e;
      }
    }
  }
}
