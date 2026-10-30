package app.soundlink.android;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** One read-only file, shared only after the user opens Android's share sheet. */
public final class DiagnosticsProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    if (!"/sound-link-diagnostics.zip".equals(uri.getPath())) throw new FileNotFoundException();
    return new File(getContext().getCacheDir(), "sound-link-diagnostics.zip");
  }

  public String getType(Uri uri) {
    return "application/zip";
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File f = file(uri);
      String[] columns =
          projection != null
              ? projection
              : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++)
        values[i] =
            OpenableColumns.DISPLAY_NAME.equals(columns[i])
                ? f.getName()
                : OpenableColumns.SIZE.equals(columns[i]) ? f.length() : null;
      cursor.addRow(values);
      return cursor;
    } catch (Exception e) {
      return null;
    }
  }

  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri uri, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri uri, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
