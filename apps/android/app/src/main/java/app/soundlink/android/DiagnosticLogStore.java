package app.soundlink.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded, private JSONL files; caller serializes access. */
final class DiagnosticLogStore {
  final File directory;
  final long limit;
  final int count;

  DiagnosticLogStore(File directory, long limit, int count) throws IOException {
    this.directory = directory;
    this.limit = limit;
    this.count = count;
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create diagnostics directory");
  }

  File file(int index) {
    return new File(directory, "events-" + index + ".jsonl");
  }

  void append(String line) throws IOException {
    byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
    if (data.length > limit) throw new IOException("Diagnostic record exceeds file limit");
    if (file(0).length() + data.length > limit) {
      Files.deleteIfExists(file(count - 1).toPath());
      for (int i = count - 2; i >= 0; i--)
        if (file(i).exists())
          Files.move(file(i).toPath(), file(i + 1).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    try (FileOutputStream out = new FileOutputStream(file(0), true)) {
      out.write(data);
    }
  }

  List<File> chronologicalFiles() {
    List<File> files = new ArrayList<>();
    for (int i = count - 1; i >= 0; i--) if (file(i).exists()) files.add(file(i));
    return files;
  }
}
