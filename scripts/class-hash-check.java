import dev.apksim.dex.ClassHash;
import dev.apksim.dex.ClassTranslator;
import dev.apksim.dex.DexFile;
import dev.apksim.dex.Hierarchy;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Checks {@link ClassHash} (the class cache's content key) on two builds of an app: every class is hashed and
 * translated in each, and a class with the same hash in both must translate to the same bytes (a hash that matched
 * while the translation differed would serve a stale class). Prints how many classes hash (the rest, null, are keyed
 * by their DEX file only), how many match across the builds and how many classes translate identically yet hash
 * differently (reuse the hash misses). Exit 1 on a mismatch.
 *
 * <pre>
 * java -Xmx4g -cp "work/apksim.jar:$(ls work/lib/*.jar | tr '\n' :)" scripts/class-hash-check.java old.apk new.apk
 * </pre>
 */
public class ClassHashCheck {
  record Result(String hash, String bytes) {}

  static Map<String, Result> run(String apk) throws Exception {
    List<DexFile> dexes = new ArrayList<>();
    try (ZipFile z = new ZipFile(apk)) {
      for (var e : Collections.list(z.entries())) {
        if (e.getName().matches("classes\\d*\\.dex")) dexes.add(new DexFile(ByteBuffer.wrap(z.getInputStream(e).readAllBytes())));
      }
    }
    Set<String> interfaces = new HashSet<>();
    Map<String, int[]> where = new HashMap<>();
    for (int k = 0; k < dexes.size(); k++) {
      DexFile d = dexes.get(k);
      for (int i = 0; i < d.classDefsSize; i++) {
        if ((d.classAccess(i) & 0x200) != 0) interfaces.add(d.className(i));
        String t = d.className(i);
        where.putIfAbsent(t.substring(1, t.length() - 1), new int[] {k, i});
      }
    }
    Hierarchy all = (cls, name, desc) -> {
      int[] at = where.get(cls);
      if (at == null) return null;
      DexFile d = dexes.get(at[0]);
      return new Hierarchy.Shape(d.superclassOf(at[1]), d.interfacesOf(at[1]), d.declaresMethod(at[1], name, desc));
    };
    Map<String, Result> out = new HashMap<>();
    MessageDigest md = MessageDigest.getInstance("SHA-1");
    for (DexFile d : dexes) {
      for (int i = 0; i < d.classDefsSize; i++) {
        byte[] h = ClassHash.of(d, i);
        String bytes;
        try {
          bytes = HexFormat.of().formatHex(md.digest(new ClassTranslator(d, i, n -> interfaces.contains("L" + n + ";"), all).translate()));
        } catch (Throwable t) {
          bytes = "ERROR";
        }
        out.put(d.className(i), new Result(h == null ? null : HexFormat.of().formatHex(h), bytes));
      }
    }
    return out;
  }

  public static void main(String[] a) throws Exception {
    Map<String, Result> x = run(a[0]);
    Map<String, Result> y = run(a[1]);
    int nullHash = 0, both = 0, hashSame = 0, mismatch = 0, bytesSameHashDiffers = 0, bytesSame = 0;
    for (Map.Entry<String, Result> e : y.entrySet()) {
      Result r = e.getValue();
      if (r.hash == null) nullHash++;
      Result o = x.get(e.getKey());
      if (o == null) continue;
      both++;
      boolean sameBytes = r.bytes.equals(o.bytes) && !r.bytes.equals("ERROR");
      if (sameBytes) bytesSame++;
      if (r.hash != null && r.hash.equals(o.hash)) {
        hashSame++;
        if (!sameBytes) {
          mismatch++;
          if (mismatch <= 10) System.out.println("MISMATCH " + e.getKey());
        }
      } else if (sameBytes) {
        bytesSameHashDiffers++;
        if (bytesSameHashDiffers <= 5) System.out.println("missed " + e.getKey());
      }
    }
    System.out.printf("%d classes in the new build, %d in both; %d without a hash%n", y.size(), both, nullHash);
    System.out.printf("same hash: %d (translation differs: %d); same translation: %d (hash differs: %d)%n", hashSame, mismatch, bytesSame, bytesSameHashDiffers);
    System.exit(mismatch == 0 ? 0 : 1);
  }
}
