package dev.live.offline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Bounded local model provisioning; no network or online dependency. */
final class ModelBundle {
  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format("%02x", b & 255));
    return out.toString();
  }

  private static File safe(File root, String path) throws IOException {
    if (!path.matches("(asr|vlm|tts|observer)/[A-Za-z0-9_.-]+")) throw new IOException("모델 경로 오류");
    File file = new File(root, path);
    if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException("경로 범위 오류");
    return file;
  }

  static void verify(File root) throws Exception {
    JSONObject manifest =
        new JSONObject(
            new String(
                java.nio.file.Files.readAllBytes(new File(root, "manifest.json").toPath()),
                StandardCharsets.UTF_8));
    JSONArray files = manifest.getJSONArray("files");
    if (files.length() > 200) throw new IOException("파일 수 초과");
    Set<String> names = new HashSet<>();
    long total = 0;
    for (int i = 0; i < files.length(); i++) {
      JSONObject entry = files.getJSONObject(i);
      String name = entry.getString("path");
      if (!names.add(name)) throw new IOException("중복 파일");
      File file = safe(root, name);
      long expected = entry.getLong("bytes");
      total += expected;
      if (expected < 0
          || expected > 4L * 1024 * 1024 * 1024
          || total > 8L * 1024 * 1024 * 1024
          || file.length() != expected) throw new IOException("모델 파일 크기 오류");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(file)) {
        byte[] buffer = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      if (!hex(digest.digest()).equals(entry.getString("sha256")))
        throw new IOException("모델 체크섬 오류");
    }
    for (String role : new String[] {"asr", "vlm", "tts"})
      if (!names.contains(role + "/config.json") || !names.contains(role + "/llm_config.json"))
        throw new IOException("필수 모델 누락");
    if (new File(root,"observer").exists())
      for (String name : new String[]{"config.json","llm_config.json","llm.mnn","llm.mnn.weight","visual.mnn","visual.mnn.weight","tokenizer.mtok"})
        if (!names.contains("observer/"+name)) throw new IOException("관찰 모델 파일 누락");
  }

  static File install(InputStream input, File parent) throws Exception {
    File pending = new File(parent, "models.pending");
    MainActivity.remove(pending);
    pending.mkdirs();
    try (ZipInputStream zip = new ZipInputStream(input)) {
      ZipEntry first = zip.getNextEntry();
      if (first == null || !first.getName().equals("manifest.json"))
        throw new IOException("manifest가 첫 파일이어야 합니다.");
      ByteArrayOutputStream data = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024 * 1024];
      int n;
      while ((n = zip.read(buffer)) != -1) {
        if (data.size() + n > 512 * 1024) throw new IOException("manifest 크기 초과");
        data.write(buffer, 0, n);
      }
      JSONObject manifest = new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
      JSONArray files = manifest.getJSONArray("files");
      if (files.length() > 200) throw new IOException("파일 수 초과");
      Map<String, Long> expected = new HashMap<>();
      long total = 0;
      for (int i = 0; i < files.length(); i++) {
        JSONObject entry = files.getJSONObject(i);
        String path = entry.getString("path");
        safe(pending, path);
        long size = entry.getLong("bytes");
        if (size < 0 || size > 4L * 1024 * 1024 * 1024 || expected.put(path, size) != null)
          throw new IOException("manifest 파일 오류");
        total += size;
      }
      if (total > 8L * 1024 * 1024 * 1024 || parent.getUsableSpace() < total + 512L * 1024 * 1024)
        throw new IOException("모델 저장 공간이 부족합니다.");
      try (FileOutputStream out = new FileOutputStream(new File(pending, "manifest.json"))) {
        data.writeTo(out);
      }
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Long size = expected.remove(entry.getName());
        if (size == null) throw new IOException("예상하지 않은 파일");
        File file = safe(pending, entry.getName());
        file.getParentFile().mkdirs();
        long written = 0;
        try (FileOutputStream out = new FileOutputStream(file)) {
          while ((n = zip.read(buffer)) != -1) {
            written += n;
            if (written > size) throw new IOException("파일 크기 초과");
            out.write(buffer, 0, n);
          }
        }
        if (written != size) throw new IOException("잘린 파일");
      }
      if (!expected.isEmpty()) throw new IOException("누락된 파일");
    } catch (Exception e) {
      MainActivity.remove(pending);
      throw e;
    }
    try {
      verify(pending);
    } catch (Exception e) {
      MainActivity.remove(pending);
      throw e;
    }
    File current = new File(parent, "models"), previous = new File(parent, "models.previous");
    MainActivity.remove(previous);
    if (current.exists() && !current.renameTo(previous)) throw new IOException("기존 모델 이동 실패");
    if (!pending.renameTo(current)) {
      previous.renameTo(current);
      throw new IOException("모델 설치 실패");
    }
    MainActivity.remove(previous);
    return current;
  }
}
