import dev.apksim.dex.ClassTranslator;
import dev.apksim.dex.DexFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Translates one class of a .dex file the way apksim does, to read the JVM code with javap: how the translator's
 * bugs show up (an int register typed as a reference loads aconst_null and compares with if_acmpne).
 *
 * <pre>
 * unzip -o app.apk 'classes*.dex' -d /tmp/dex     # dexdump -d from build-tools shows the Dalvik side
 * java -cp "work/apksim.jar:$(ls work/lib/*.jar | tr '\n' :)" scripts/translate-class.java /tmp/dex/classes2.dex \
 *     'Lcom/example/Foo;' /tmp/Foo.class
 * javap -c -p /tmp/Foo.class
 * </pre>
 *
 * Every class counts as a non-interface (the live translator asks the app); add -Dapksim.debugTypes=com/example/Foo.bar
 * to report where a register of that method is typed both a reference and a primitive.
 */
public class TranslateClass {
  public static void main(String[] a) throws Exception {
    DexFile dex = new DexFile(ByteBuffer.wrap(Files.readAllBytes(Path.of(a[0]))));
    for (int i = 0; i < dex.classDefsSize; i++) {
      if (dex.className(i).equals(a[1])) {
        byte[] out = new ClassTranslator(dex, i, n -> false).translate();
        Files.write(Path.of(a[2]), out);
        System.out.println("wrote " + a[2] + " (" + out.length + " bytes)");
        return;
      }
    }
    System.err.println(a[1] + " is not in " + a[0]);
    System.exit(1);
  }
}
