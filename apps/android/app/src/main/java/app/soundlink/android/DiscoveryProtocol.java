package app.soundlink.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Pure wire decoder shared by the GATT client and JVM security tests. */
final class DiscoveryProtocol {
  static final UUID CHALLENGE = UUID.fromString("27491e7a-7f1c-4e92-a002-534c00000001");
  static final UUID ENDPOINT = UUID.fromString("27491e7a-7f1c-4e92-a002-534c00000002");
  static final byte[] DOMAIN = "SoundLink/discovery/v1\n".getBytes(StandardCharsets.UTF_8);

  static UUID serviceUUID(String pin) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(DOMAIN);
    byte[] bytes = digest.digest(pin.getBytes(StandardCharsets.UTF_8));
    bytes[6] = (byte) ((bytes[6] & 15) | 0x50);
    bytes[8] = (byte) ((bytes[8] & 63) | 0x80);
    ByteBuffer b = ByteBuffer.wrap(bytes);
    return new UUID(b.getLong(), b.getLong());
  }

  record Endpoint(boolean available, long revision, int port, List<String> hosts) {}

  static Endpoint verify(byte[] packet, byte[] nonce, String pin) throws Exception {
    if (packet == null || packet.length < 159 || packet.length > 183 || nonce.length != 16)
      throw new IOException("蓝牙地址描述长度错误");
    ByteBuffer b = ByteBuffer.wrap(packet);
    if (b.get() != 1) throw new IOException("不兼容的蓝牙地址协议");
    int flags = Byte.toUnsignedInt(b.get());
    if (flags > 1) throw new IOException("无效的 Mac 可用状态");
    long revision = b.getLong();
    byte[] actualNonce = new byte[16];
    b.get(actualNonce);
    if (!MessageDigest.isEqual(nonce, actualNonce)) throw new IOException("蓝牙挑战已过期");
    byte[] rawKey = new byte[65];
    b.get(rawKey);
    if (rawKey[0] != 4) throw new IOException("蓝牙身份格式错误");
    byte[] header = HexFormat.of().parseHex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    byte[] der = ByteBuffer.allocate(91).put(header).put(rawKey).array();
    if (!pin.equals(Link.hash(der))) throw new IOException("附近设备不是已配对的 Mac");
    int port = Short.toUnsignedInt(b.getShort()), count = Byte.toUnsignedInt(b.get());
    if (count > 4 || b.remaining() < count * 4 + 65) throw new IOException("蓝牙地址数量错误");
    ArrayList<String> hosts = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String host =
          Byte.toUnsignedInt(b.get())
              + "."
              + Byte.toUnsignedInt(b.get())
              + "."
              + Byte.toUnsignedInt(b.get())
              + "."
              + Byte.toUnsignedInt(b.get());
      if (!Link.privateIPv4(host)) throw new IOException("拒绝非局域网蓝牙地址");
      hosts.add(host);
    }
    int bodySize = b.position(), signatureSize = Byte.toUnsignedInt(b.get());
    if (signatureSize < 64 || signatureSize > 72 || b.remaining() != signatureSize)
      throw new IOException("蓝牙签名格式错误");
    byte[] signature = new byte[signatureSize];
    b.get(signature);
    Signature verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der)));
    verifier.update(DOMAIN);
    verifier.update(packet, 0, bodySize);
    if (!verifier.verify(signature)) throw new IOException("蓝牙地址签名校验失败");
    if (flags == 1 && (port == 0 || hosts.isEmpty())) throw new IOException("Mac 没有可用连接地址");
    return new Endpoint(flags == 1, revision, port, List.copyOf(hosts));
  }
}
