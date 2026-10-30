package app.soundlink.android;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class DiagnosticLogStoreTest {
  @Test
  public void rotationIsBoundedAndSurvivesRecreation() throws Exception {
    Path dir = Files.createTempDirectory("soundlink-log-test");
    try {
      var store = new DiagnosticLogStore(dir.toFile(), 24, 3);
      for (int i = 0; i < 20; i++) store.append("{\"seq\":" + i + "}");
      assertTrue(store.chronologicalFiles().size() <= 3);
      List<Integer> seq = new ArrayList<>();
      for (File f : store.chronologicalFiles()) {
        assertTrue(f.length() <= 24);
        for (String line : Files.readAllLines(f.toPath()))
          seq.add(new org.json.JSONObject(line).getInt("seq"));
      }
      for (int i = 1; i < seq.size(); i++) assertEquals(seq.get(i - 1) + 1, seq.get(i).intValue());
      assertEquals(19, seq.get(seq.size() - 1).intValue());
      var reopened = new DiagnosticLogStore(dir.toFile(), 24, 3);
      reopened.append("{\"seq\":20}");
      assertTrue(Files.readString(reopened.file(0).toPath()).contains("20"));
    } finally {
      try (var files = Files.list(dir)) {
        for (Path p : files.toList()) Files.delete(p);
      }
      Files.delete(dir);
    }
  }

  @Test
  public void oversizedEventDoesNotDestroyExistingHistory() throws Exception {
    Path dir = Files.createTempDirectory("soundlink-log-limit");
    try {
      var store = new DiagnosticLogStore(dir.toFile(), 24, 2);
      store.append("{\"ok\":true}");
      assertThrows(IOException.class, () -> store.append("x".repeat(30)));
      assertEquals("{\"ok\":true}\n", Files.readString(store.file(0).toPath()));
    } finally {
      try (var files = Files.list(dir)) {
        for (Path p : files.toList()) Files.delete(p);
      }
      Files.delete(dir);
    }
  }
}
