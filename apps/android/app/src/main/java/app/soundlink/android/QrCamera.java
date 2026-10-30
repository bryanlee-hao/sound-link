package app.soundlink.android;

import android.app.Activity;
import android.content.Intent;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.Surface;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.nio.ByteBuffer;
import java.util.*;

/** Camera transport only. Compose owns all scanner UI and the preview surface. */
final class QrCamera implements AutoCloseable {
  private final Activity activity;
  private final Surface surface;
  private final Spec spec;
  private final java.util.function.Consumer<String> hint;
  private HandlerThread thread;
  private Handler cameraHandler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader reader;
  private volatile boolean finished;
  private long last;

  QrCamera(
      Activity activity, Surface surface, Spec spec, java.util.function.Consumer<String> hint) {
    this.activity = activity;
    this.surface = surface;
    this.spec = spec;
    this.hint = hint;
    thread = new HandlerThread("qr-camera");
    thread.start();
    cameraHandler = new Handler(thread.getLooper());
  }

  static final class Spec {
    final String id;
    final int width, height, rotation;

    Spec(String id, android.util.Size size, int rotation) {
      this.id = id;
      width = size.getWidth();
      height = size.getHeight();
      this.rotation = rotation;
    }
  }

  static Spec spec(Activity activity) throws Exception {
    CameraManager manager = activity.getSystemService(CameraManager.class);
    for (String id : manager.getCameraIdList()) {
      CameraCharacteristics info = manager.getCameraCharacteristics(id);
      if (!Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
          .equals(info.get(CameraCharacteristics.LENS_FACING))) continue;
      var map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      var previewSizes = Arrays.asList(map.getOutputSizes(android.graphics.SurfaceTexture.class));
      var size =
          Arrays.stream(map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888))
              .filter(previewSizes::contains)
              .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
              .min(
                  Comparator.comparingInt(s -> Math.abs(s.getWidth() * s.getHeight() - 1280 * 720)))
              .orElseThrow(() -> new IllegalStateException("No compatible preview size"));
      int rotation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
      return new Spec(id, size, rotation);
    }
    throw new IllegalStateException("No rear camera");
  }

  private String getString(int id) {
    return activity.getString(id);
  }

  private void error(String text) {
    activity.runOnUiThread(
        () -> hint.accept(text + getString(R.string.message_ngo_back_and_try_again)));
  }

  void open() {
    if (cameraHandler == null || camera != null) return;
    try {
      if (activity.checkSelfPermission(android.Manifest.permission.CAMERA)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        error(getString(R.string.message_allow_camera_access));
        return;
      }
      CameraManager manager = activity.getSystemService(CameraManager.class);
      reader =
          ImageReader.newInstance(
              spec.width, spec.height, android.graphics.ImageFormat.YUV_420_888, 2);
      reader.setOnImageAvailableListener(this::decode, cameraHandler);
      manager.openCamera(
          spec.id,
          new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice d) {
              camera = d;
              if (finished) {
                d.close();
                return;
              }
              configure();
            }

            public void onDisconnected(CameraDevice d) {
              d.close();
              error(getString(R.string.message_camera_disconnected));
            }

            public void onError(CameraDevice d, int e) {
              d.close();
              error(getString(R.string.message_couldn_t_open_camera));
            }
          },
          cameraHandler);
    } catch (Exception e) {
      error(UserMessage.error(activity, e));
    }
  }

  private void configure() {
    try {
      CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      request.addTarget(surface);
      request.addTarget(reader.getSurface());
      request.set(
          CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      camera.createCaptureSession(
          Arrays.asList(surface, reader.getSurface()),
          new CameraCaptureSession.StateCallback() {
            public void onConfigured(CameraCaptureSession s) {
              session = s;
              if (finished) {
                s.close();
                return;
              }
              try {
                s.setRepeatingRequest(
                    request.build(),
                    new CameraCaptureSession.CaptureCallback() {
                      private boolean shown;

                      @Override
                      public void onCaptureCompleted(
                          CameraCaptureSession session,
                          CaptureRequest request,
                          TotalCaptureResult result) {
                        if (!shown && !finished) {
                          shown = true;
                          activity.runOnUiThread(
                              () -> {
                                if (!finished) hint.accept(getString(R.string.scan_hint));
                              });
                        }
                      }
                    },
                    cameraHandler);
              } catch (Exception e) {
                error(getString(R.string.message_camera_preview_unavailable));
              }
            }

            public void onConfigureFailed(CameraCaptureSession s) {
              error(getString(R.string.message_couldn_t_start_scanner));
            }
          },
          cameraHandler);
    } catch (Exception e) {
      error(getString(R.string.message_couldn_t_start_scanner));
    }
  }

  private void decode(ImageReader source) {
    try (Image im = source.acquireLatestImage()) {
      if (im == null || finished || SystemClock.elapsedRealtime() - last < 220) return;
      last = SystemClock.elapsedRealtime();
      int w = im.getWidth(), h = im.getHeight();
      Image.Plane plane = im.getPlanes()[0];
      ByteBuffer bytes = plane.getBuffer();
      byte[] data = new byte[w * h];
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
          data[y * w + x] = bytes.get(y * plane.getRowStride() + x * plane.getPixelStride());
      LuminanceSource luminance = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
      MultiFormatReader decoder = new MultiFormatReader();
      Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
      hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
      hints.put(DecodeHintType.TRY_HARDER, true);
      Result result = decoder.decode(new BinaryBitmap(new HybridBinarizer(luminance)), hints);
      Link.code(result.getText());
      finished = true;
      activity.runOnUiThread(
          () -> {
            activity.setResult(Activity.RESULT_OK, new Intent().putExtra("code", result.getText()));
            activity.finish();
          });
    } catch (NotFoundException ignored) {
    } catch (Exception e) {
      if (!(e instanceof com.google.zxing.ReaderException))
        activity.runOnUiThread(
            () -> hint.accept(getString(R.string.message_scan_a_sound_link_pairing_code)));
    }
  }

  public void close() {
    finished = true;
    if (session != null) {
      session.close();
      session = null;
    }
    if (camera != null) {
      camera.close();
      camera = null;
    }
    if (reader != null) {
      reader.close();
      reader = null;
    }
    if (thread != null) {
      thread.quitSafely();
      thread = null;
    }
    cameraHandler = null;
  }
}
