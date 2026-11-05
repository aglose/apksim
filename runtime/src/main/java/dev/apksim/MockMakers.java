package dev.apksim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Which Mockito mock maker the APK's tests get: `--mock-maker MODE` (-Dapksim.mockMaker).
 *
 * <p>Mockito picks its mock maker from the resource {@value #RESOURCE}, which a test APK ships when it uses
 * mockito-android or dexmaker. Those makers are for ART: mockito-android's AndroidByteBuddyMockMaker sees a regular JVM
 * and logs a warning from its constructor while Mockito's plugin registry is still being built, so the first mock (often
 * a MockitoJUnit rule in a base class, so every test) throws "NullPointerException: Plugins.registry is null"; dexmaker's
 * makers write DEX files. apksim translates the APK's classes into JVM classes, so Mockito's own JVM makers work on them.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code auto} (default): an ART maker the APK names is swapped for its JVM counterpart ({@link #JVM_FOR_ART});
 *       any other maker is kept;
 *   <li>{@code keep}: the APK's choice, untouched;
 *   <li>{@code subclass} or {@code inline}: Mockito's {@code mock-maker-subclass} or {@code mock-maker-inline}, whatever
 *       the APK names. {@code inline} retransforms the classes it mocks, so it needs Byte Buddy's agent to attach
 *       ({@code --jvm -Djdk.attach.allowAttachSelf=true --jvm -XX:+EnableDynamicAgentLoading}, and
 *       {@code --jvm -Dnet.bytebuddy.experimental=true} for a Byte Buddy older than the JVM), and it mocks JDK types
 *       only: the APK's classes are translated without stack map frames, which a retransformed class must have
 *       (VerifyError). So {@code auto} maps even dexmaker's inline maker to the subclass maker;
 *   <li>anything else: used as the mock maker as given, a Mockito alias or the class name of a maker on the test
 *       classpath.
 * </ul>
 * Only applies when the test APK ships the resource: without it Mockito already uses its own default.
 */
final class MockMakers {
  private MockMakers() {}

  static final String RESOURCE = "mockito-extensions/org.mockito.plugins.MockMaker";

  /** ART mock makers and the JVM maker that stands in for each in {@code auto} mode. */
  static final Map<String, String> JVM_FOR_ART = Map.of(
      "org.mockito.android.internal.creation.AndroidByteBuddyMockMaker", "mock-maker-subclass",
      "com.android.dx.mockito.DexmakerMockMaker", "mock-maker-subclass",
      "com.android.dx.mockito.inline.InlineDexmakerMockMaker", "mock-maker-subclass");

  /** The maker to serve for the APK's declared one, under {@code mode}. */
  static String choose(String declared, String mode) {
    return switch (mode == null || mode.isBlank() ? "auto" : mode.trim()) {
      case "auto" -> JVM_FOR_ART.getOrDefault(declared, declared);
      case "keep" -> declared;
      case "subclass" -> "mock-maker-subclass";
      case "inline" -> "mock-maker-inline";
      default -> mode.trim();
    };
  }

  /** The resource's contents as this run serves them; logs a swap. */
  static InputStream serve(InputStream apkResource) throws IOException {
    String declared;
    try (apkResource) {
      declared = new String(apkResource.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    String mode = System.getProperty("apksim.mockMaker", "auto");
    String served = choose(declared, mode);
    if (!served.equals(declared)) {
      System.err.println("[apksim] Mockito: " + served + " in place of " + declared + " (--mock-maker " + mode + ")");
    }
    return new ByteArrayInputStream(served.getBytes(StandardCharsets.UTF_8));
  }
}
