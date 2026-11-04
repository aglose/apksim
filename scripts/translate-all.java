import dev.apksim.dex.ClassTranslator;
import dev.apksim.dex.DexFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Translates every class of an APK the way apksim does, PASSES times, prints each pass's time, and writes one line per
 * class (dex:class sha1) to OUT. For translator work: two runs with different jars must write the same OUT (output
 * byte-identical), and a later pass's time is the steady state (the first includes JIT warm-up). Add
 * -XX:StartFlightRecording=filename=t.jfr to profile it.
 *
 * <pre>
 * java -Xmx4g -cp "work/apksim.jar:$(ls work/lib/*.jar | tr '\n' :)" scripts/translate-all.java app.apk out.txt 3
 * </pre>
 *
 * Interfaces are the APK's own (the live translator asks the app, which also knows framework interfaces).
 */
public class TranslateAll {
  public static void main(String[] a) throws Exception {
    List<DexFile> dexes = new ArrayList<>();
    try (ZipFile z = new ZipFile(a[0])) {
      for (var e : Collections.list(z.entries())) {
        if (e.getName().matches("classes\\d*\\.dex")) dexes.add(new DexFile(ByteBuffer.wrap(z.getInputStream(e).readAllBytes())));
      }
    }
    Set<String> interfaces = new HashSet<>();
    for (DexFile d : dexes) {
      for (int i = 0; i < d.classDefsSize; i++) if ((d.classAccess(i) & 0x200) != 0) interfaces.add(d.className(i));
    }
    int passes = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    StringBuilder out = new StringBuilder();
    for (int p = 0; p < passes; p++) {
      boolean last = p == passes - 1;
      long t0 = System.nanoTime();
      int n = 0, errors = 0;
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      for (int di = 0; di < dexes.size(); di++) {
        DexFile d = dexes.get(di);
        for (int i = 0; i < d.classDefsSize; i++) {
          String h;
          try {
            byte[] b = new ClassTranslator(d, i, name -> interfaces.contains("L" + name + ";")).translate();
            h = last ? HexFormat.of().formatHex(md.digest(b)) : "";
          } catch (Throwable t) {
            errors++;
            h = "ERROR " + t;
          }
          n++;
          if (last) out.append(di).append(':').append(d.className(i)).append(' ').append(h).append('\n');
        }
      }
      System.out.printf("pass %d: %d classes, %d errors, %d ms%n", p, n, errors, (System.nanoTime() - t0) / 1_000_000);
    }
    Files.writeString(Path.of(a[1]), out);
  }
}
