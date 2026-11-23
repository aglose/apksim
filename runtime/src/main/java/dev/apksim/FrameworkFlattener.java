package dev.apksim;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.robolectric.internal.ShadowProvider;

/**
 * Offline framework-jar trampoline flattener (docs/perf-plan.md, B2).
 *
 * <p>Robolectric's instrumented Android framework jar wraps every method, constructor, static initializer, and
 * {@code $$robo$init} call in an {@code invokedynamic} or reflective {@code classInitializing} trampoline. On every
 * cold launch, linking those call sites spins ~1,100 runtime {@code LambdaForm$MH} hidden classes and burns ~20-30%
 * of startup time.
 *
 * <p>For every framework method that NO shadow in Robolectric's {@link ShadowProvider}s or {@code apksim.jar} can ever
 * intercept (non-native methods only, so {@link Gaps} keeps counting unshadowed natives), this tool rewrites the
 * {@code invokedynamic} directly into the underlying {@code invokespecial}/{@code invokestatic} call to the class's
 * {@code $$robo$$} body. It likewise rewrites unshadowed {@code <clinit>} trampolines to call
 * {@code __staticInitializer__()} directly, and rewrites {@code $$robo$init()} on classes with no shadow anywhere in
 * their superclass chain to store {@code InvokeDynamicSupport.NO_SHADOW} directly.
 *
 * <p>All {@code $$robo$$} bodies remain intact (so {@code Shadow.directlyOn} and {@code Shadow.invokeConstructor} keep
 * working), all shadowed methods and all native methods stay untouched, and the original
 * {@code android-all-instrumented-*.jar} is never modified (keeping {@code APKSIM_FLAT_FRAMEWORK=0} as an instant A/B
 * switch and preserving {@code resources.arsc} in {@code robolectric.dependency.dir}).
 */
public final class FrameworkFlattener {
  private static final int ASM = Opcodes.ASM9;
  private static final String IDS = "org/robolectric/internal/bytecode/InvokeDynamicSupport";
  private static final String RI = "org/robolectric/internal/bytecode/RobolectricInternals";
  private static final String IMPLEMENTS_DESC = "Lorg/robolectric/annotation/Implements;";
  private static final String IMPLEMENTATION_DESC = "Lorg/robolectric/annotation/Implementation;";
  private static final String VERSION = "b2-v4";
  /** Sentinel set meaning every method of a shadowed target class must stay untouched. */
  private static final Set<String> OPAQUE = Set.of("<OPAQUE>");

  private FrameworkFlattener() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("usage: FrameworkFlattener <framework-jar> <out-jar> <jar-or-dir>...");
      System.exit(2);
    }
    Path frameworkJar = Path.of(args[0]).toAbsolutePath();
    Path outJar = Path.of(args[1]).toAbsolutePath();
    Path stampFile = outJar.resolveSibling(outJar.getFileName() + ".stamp");
    List<Path> scanJars = new ArrayList<>();
    for (int i = 2; i < args.length; i++) {
      Path p = Path.of(args[i]).toAbsolutePath();
      if (Files.isDirectory(p)) {
        try (var s = Files.list(p)) {
          s.filter(f -> f.getFileName().toString().endsWith(".jar"))
              .filter(f -> !f.getFileName().toString().startsWith("android-all-instrumented-"))
              .filter(f -> !f.getFileName().toString().startsWith("nativeruntime-dist-"))
              .sorted()
              .forEach(scanJars::add);
        }
      } else if (Files.isRegularFile(p)) {
        scanJars.add(p);
      }
    }

    long t0 = System.nanoTime();
    ShadowIndex shadows = discoverShadows(scanJars);
    String stamp = computeStamp(frameworkJar, shadows);
    if (Files.isRegularFile(outJar) && Files.isRegularFile(stampFile)
        && stamp.equals(Files.readString(stampFile).trim())) {
      return;
    }

    Stats stats = flatten(frameworkJar, outJar, shadows);
    Files.writeString(stampFile, stamp + "\n");
    long ms = (System.nanoTime() - t0) / 1_000_000;
    System.err.printf("[apksim] flattened %s -> %s in %d ms (classes=%d shadowedTargets=%d indyFlat=%d indyKept=%d clinitFlat=%d initFlat=%d)%n",
        frameworkJar.getFileName(), outJar.getFileName(), ms,
        stats.classes.get(), shadows.shadowedTargets.size(),
        stats.indyFlat.get(), stats.indyKept.get(), stats.clinitFlat.get(), stats.initFlat.get());
  }

  private static final class ShadowIndex {
    /** Normalized dot-form and canonical-dot-form names of every shadowed target class. */
    final Set<String> shadowedTargets = new HashSet<>();
    /** Target name (both binary dot and canonical dot forms) -> set of shadowed method names (or OPAQUE). */
    final Map<String, Set<String>> shadowedMethods = new HashMap<>();

    boolean isShadowed(String internalName) {
      String dot = internalName.replace('/', '.');
      if (shadowedTargets.contains(dot)) return true;
      String canon = dot.replace('$', '.');
      return shadowedTargets.contains(canon);
    }

    Set<String> methodsFor(String internalName) {
      String dot = internalName.replace('/', '.');
      Set<String> a = shadowedMethods.get(dot);
      String canon = dot.replace('$', '.');
      Set<String> b = dot.equals(canon) ? null : shadowedMethods.get(canon);
      if (a == OPAQUE || b == OPAQUE) return OPAQUE;
      if (a == null) return b;
      if (b == null) return a;
      Set<String> merged = new HashSet<>(a);
      merged.addAll(b);
      return merged;
    }
  }

  private static final class ShadowClassMeta {
    final String internalName;
    String superName;
    final Set<String> methodNames = new HashSet<>();
    final List<String> targets = new ArrayList<>();

    ShadowClassMeta(String internalName) {
      this.internalName = internalName;
    }
  }

  /**
   * Discovers every shadow class from both Robolectric's {@link ShadowProvider} SPI and a full ASM scan of
   * {@code @Implements} across all runtime and library jars.
   */
  private static ShadowIndex discoverShadows(List<Path> jars) throws IOException {
    Map<String, ShadowClassMeta> allClasses = new HashMap<>();
    for (Path jar : jars) {
      try (ZipFile zf = new ZipFile(jar.toFile())) {
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
          ZipEntry e = en.nextElement();
          String name = e.getName();
          if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
          byte[] bytes;
          try (InputStream in = zf.getInputStream(e)) {
            bytes = in.readAllBytes();
          }
          ShadowClassMeta meta = inspectClass(bytes);
          if (meta != null) allClasses.putIfAbsent(meta.internalName, meta);
        }
      }
    }

    // Target dot/canonical name -> set of shadow internal names (null value means OPAQUE).
    Map<String, Set<String>> targetToShadows = new HashMap<>();
    Set<String> opaqueTargets = new HashSet<>();

    // 1) Every @Implements class found in the jar scan (covers Robolectric defaults, pickers, and all apksim shadows).
    for (ShadowClassMeta meta : allClasses.values()) {
      for (String target : meta.targets) {
        addTargetShadow(targetToShadows, target, meta.internalName);
      }
    }

    // 2) Cross-check Robolectric's ShadowProvider SPI (getShadows and getShadowPickerMap).
    for (ShadowProvider provider : ServiceLoader.load(ShadowProvider.class)) {
      for (Map.Entry<String, String> entry : provider.getShadows()) {
        String target = entry.getKey();
        String shadowInternal = entry.getValue().replace('.', '/');
        if (allClasses.containsKey(shadowInternal)) {
          addTargetShadow(targetToShadows, target, shadowInternal);
        } else {
          markOpaque(opaqueTargets, target);
        }
      }
      for (Map.Entry<String, String> entry : provider.getShadowPickerMap().entrySet()) {
        String target = entry.getKey();
        // Ensure target is recorded as shadowed even if a picker could return null or a class not yet seen.
        addTargetShadow(targetToShadows, target, null);
      }
    }

    ShadowIndex index = new ShadowIndex();
    Set<String> allTargets = new HashSet<>(targetToShadows.keySet());
    allTargets.addAll(opaqueTargets);
    for (String target : allTargets) {
      index.shadowedTargets.add(target);
      if (opaqueTargets.contains(target)) {
        index.shadowedMethods.put(target, OPAQUE);
        continue;
      }
      Set<String> shadowNames = targetToShadows.get(target);
      Set<String> methods = new HashSet<>();
      boolean opaque = false;
      if (shadowNames != null) {
        for (String sName : shadowNames) {
          if (sName == null) continue;
          if (!collectShadowMethods(sName, allClasses, methods, new HashSet<>())) {
            opaque = true;
            break;
          }
        }
      }
      index.shadowedMethods.put(target, opaque ? OPAQUE : methods);
    }
    return index;
  }

  private static void addTargetShadow(Map<String, Set<String>> map, String target, String shadowInternal) {
    String dot = target.replace('/', '.');
    String canon = dot.replace('$', '.');
    Set<String> s1 = map.computeIfAbsent(dot, k -> new HashSet<>());
    if (shadowInternal != null) s1.add(shadowInternal);
    if (!canon.equals(dot)) {
      Set<String> s2 = map.computeIfAbsent(canon, k -> new HashSet<>());
      if (shadowInternal != null) s2.add(shadowInternal);
    }
  }

  private static void markOpaque(Set<String> opaque, String target) {
    String dot = target.replace('/', '.');
    opaque.add(dot);
    opaque.add(dot.replace('$', '.'));
  }

  private static boolean collectShadowMethods(String internalName, Map<String, ShadowClassMeta> allClasses,
      Set<String> out, Set<String> visited) {
    if (internalName == null || internalName.equals("java/lang/Object")) return true;
    if (!visited.add(internalName)) return true;
    ShadowClassMeta meta = allClasses.get(internalName);
    if (meta == null) return false;
    out.addAll(meta.methodNames);
    return collectShadowMethods(meta.superName, allClasses, out, visited);
  }

  private static ShadowClassMeta inspectClass(byte[] bytes) {
    ClassReader cr = new ClassReader(bytes);
    String internalName = cr.getClassName();
    ShadowClassMeta meta = new ShadowClassMeta(internalName);
    meta.superName = cr.getSuperName();
    cr.accept(new ClassVisitor(ASM) {
      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        if (!IMPLEMENTS_DESC.equals(descriptor)) return null;
        return new AnnotationVisitor(ASM) {
          String valueClass;
          String className;

          @Override
          public void visit(String name, Object value) {
            if ("value".equals(name) && value instanceof Type t) {
              String cls = t.getClassName();
              if (!"java.lang.Object".equals(cls) && !"void".equals(cls)) valueClass = cls;
            } else if ("className".equals(name) && value instanceof String s && !s.isEmpty()) {
              className = s;
            }
          }

          @Override
          public void visitEnd() {
            if (valueClass != null) meta.targets.add(valueClass);
            if (className != null) meta.targets.add(className);
          }
        };
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (!"<init>".equals(name) && !"<clinit>".equals(name)) {
          meta.methodNames.add(name);
        }
        return new MethodVisitor(ASM) {
          @Override
          public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (!IMPLEMENTATION_DESC.equals(desc)) return null;
            return new AnnotationVisitor(ASM) {
              @Override
              public void visit(String attr, Object value) {
                if ("methodName".equals(attr) && value instanceof String s && !s.isEmpty()) {
                  meta.methodNames.add(s);
                }
              }
            };
          }
        };
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return meta;
  }

  private static String computeStamp(Path frameworkJar, ShadowIndex shadows) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update(VERSION.getBytes(StandardCharsets.UTF_8));
    md.update(frameworkJar.getFileName().toString().getBytes(StandardCharsets.UTF_8));
    md.update(Long.toString(Files.size(frameworkJar)).getBytes(StandardCharsets.UTF_8));
    md.update(Long.toString(Files.getLastModifiedTime(frameworkJar).toMillis()).getBytes(StandardCharsets.UTF_8));
    TreeMap<String, Set<String>> sorted = new TreeMap<>(shadows.shadowedMethods);
    for (Map.Entry<String, Set<String>> e : sorted.entrySet()) {
      md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
      md.update((byte) '=');
      for (String m : new TreeSet<>(e.getValue())) {
        md.update(m.getBytes(StandardCharsets.UTF_8));
        md.update((byte) ',');
      }
      md.update((byte) '\n');
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static final class Stats {
    final AtomicInteger classes = new AtomicInteger();
    final AtomicInteger indyFlat = new AtomicInteger();
    final AtomicInteger indyKept = new AtomicInteger();
    final AtomicInteger clinitFlat = new AtomicInteger();
    final AtomicInteger initFlat = new AtomicInteger();
  }

  private static boolean skipEntry(String name) {
    // Unpacked into work/runtime/ (prepare-runtime.sh) or read from work/lib/android-all-instrumented-*.jar.
    return name.startsWith("native/")
        || name.startsWith("icu/")
        || name.startsWith("fonts/")
        || name.startsWith("hyphen-data/")
        // res/ is the framework's compiled resources, read from the original jar; but the files directly in res/
        // (android/debian/vendor.mime.types) are read as class resources by MimeMap's DefaultMimeMapFactory
        // (issue 599), so they stay in the flat jar.
        || (name.startsWith("res/") && name.indexOf('/', 4) >= 0)
        || name.equals("resources.arsc");
  }

  private static Stats flatten(Path frameworkJar, Path outJar, ShadowIndex shadows) throws Exception {
    List<String> classEntries = new ArrayList<>();
    List<String> otherEntries = new ArrayList<>();
    try (ZipFile zf = new ZipFile(frameworkJar.toFile())) {
      Enumeration<? extends ZipEntry> en = zf.entries();
      while (en.hasMoreElements()) {
        ZipEntry e = en.nextElement();
        String name = e.getName();
        if (e.isDirectory() || skipEntry(name)) continue;
        if (name.endsWith(".class") && !name.equals("module-info.class")) {
          classEntries.add(name);
        } else {
          otherEntries.add(name);
        }
      }
    }

    int threads = Math.max(2, Math.min(12, Runtime.getRuntime().availableProcessors()));
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      // Pass 1: read superclass links and __staticInitializer__ presence in parallel.
      ConcurrentHashMap<String, String> superOf = new ConcurrentHashMap<>(classEntries.size() * 2);
      Set<String> hasStaticInit = ConcurrentHashMap.newKeySet();
      int chunk = Math.max(1, (classEntries.size() + threads - 1) / threads);
      List<Future<?>> pass1 = new ArrayList<>();
      for (int i = 0; i < classEntries.size(); i += chunk) {
        List<String> slice = classEntries.subList(i, Math.min(classEntries.size(), i + chunk));
        pass1.add(pool.submit(() -> {
          try (ZipFile zf = new ZipFile(frameworkJar.toFile())) {
            for (String name : slice) {
              byte[] bytes;
              try (InputStream in = zf.getInputStream(zf.getEntry(name))) {
                bytes = in.readAllBytes();
              }
              ClassReader cr = new ClassReader(bytes);
              String cls = cr.getClassName();
              String sup = cr.getSuperName();
              if (sup != null) superOf.put(cls, sup);
              cr.accept(new ClassVisitor(ASM) {
                @Override
                public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] ex) {
                  if ("__staticInitializer__".equals(mName) && "()V".equals(desc) && (access & Opcodes.ACC_STATIC) != 0) {
                    hasStaticInit.add(cls);
                  }
                  return null;
                }
              }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
          } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
          }
        }));
      }
      for (Future<?> f : pass1) f.get();

      // Precompute which classes have NO shadow anywhere in their superclass chain.
      Set<String> noShadowInHierarchy = new HashSet<>(classEntries.size());
      for (String cls : superOf.keySet()) {
        if (!hasShadowInHierarchy(cls, superOf, shadows)) noShadowInHierarchy.add(cls);
      }

      // Pass 2: rewrite classes in parallel into per-slice buffers, then stream sequentially to outJar.
      Stats stats = new Stats();
      record RewrittenEntry(String name, byte[] bytes) {}
      List<Future<List<RewrittenEntry>>> pass2 = new ArrayList<>();
      for (int i = 0; i < classEntries.size(); i += chunk) {
        List<String> slice = classEntries.subList(i, Math.min(classEntries.size(), i + chunk));
        pass2.add(pool.submit(() -> {
          List<RewrittenEntry> out = new ArrayList<>(slice.size());
          try (ZipFile zf = new ZipFile(frameworkJar.toFile())) {
            for (String name : slice) {
              byte[] bytes;
              try (InputStream in = zf.getInputStream(zf.getEntry(name))) {
                bytes = in.readAllBytes();
              }
              byte[] rewritten = rewriteClass(bytes, shadows, hasStaticInit, noShadowInHierarchy, stats);
              out.add(new RewrittenEntry(name, rewritten));
            }
          } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
          }
          return out;
        }));
      }

      Files.createDirectories(outJar.getParent());
      Path tmp = outJar.resolveSibling(outJar.getFileName() + ".new");
      try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20))) {
        zos.setLevel(Deflater.DEFAULT_COMPRESSION);
        for (Future<List<RewrittenEntry>> f : pass2) {
          for (RewrittenEntry re : f.get()) {
            ZipEntry ze = new ZipEntry(re.name());
            zos.putNextEntry(ze);
            zos.write(re.bytes());
            zos.closeEntry();
          }
        }
        try (ZipFile zf = new ZipFile(frameworkJar.toFile())) {
          for (String name : otherEntries) {
            ZipEntry orig = zf.getEntry(name);
            ZipEntry ze = new ZipEntry(name);
            zos.putNextEntry(ze);
            try (InputStream in = zf.getInputStream(orig)) {
              in.transferTo(zos);
            }
            zos.closeEntry();
          }
        }
      }
      Files.move(tmp, outJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return stats;
    } finally {
      pool.shutdownNow();
    }
  }

  private static boolean hasShadowInHierarchy(String cls, Map<String, String> superOf, ShadowIndex shadows) {
    String cur = cls;
    int guard = 0;
    while (cur != null && !cur.equals("java/lang/Object") && guard++ < 128) {
      if (shadows.isShadowed(cur)) return true;
      String next = superOf.get(cur);
      if (next == null) {
        // Superclass is outside the framework jar (typically a JDK class). Resolve via reflection.
        try {
          Class<?> c = Class.forName(cur.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
          while (c != null && c != Object.class) {
            if (shadows.isShadowed(c.getName().replace('.', '/'))) return true;
            c = c.getSuperclass();
          }
          return false;
        } catch (Throwable t) {
          // Unknown superclass: stay conservative.
          return true;
        }
      }
      cur = next;
    }
    return guard >= 128;
  }

  private static byte[] rewriteClass(byte[] bytes, ShadowIndex shadows, Set<String> hasStaticInit,
      Set<String> noShadowInHierarchy, Stats stats) {
    ClassReader cr = new ClassReader(bytes);
    String cls = cr.getClassName();
    stats.classes.incrementAndGet();
    boolean classShadowed = shadows.isShadowed(cls);
    Set<String> shadowedMethodNames = classShadowed ? shadows.methodsFor(cls) : Collections.emptySet();
    boolean canFlatClinit = hasStaticInit.contains(cls)
        && (!classShadowed || (shadowedMethodNames != OPAQUE && !shadowedMethodNames.contains("__staticInitializer__")));
    boolean canFlatInit = noShadowInHierarchy.contains(cls);

    // Track whether any instruction actually changed; if nothing changed, return original bytes untouched.
    boolean[] modified = {false};
    ClassWriter cw = new ClassWriter(0);
    cr.accept(new ClassVisitor(ASM, cw) {
      @Override
      public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, mName, desc, sig, exceptions);
        if (canFlatClinit && "<clinit>".equals(mName) && "()V".equals(desc)) {
          return new ClinitFlattener(cls, mv, modified, stats);
        }
        return new MethodVisitor(ASM, mv) {
          @Override
          public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
            if (IDS.equals(bsm.getOwner())) {
              String bsmName = bsm.getName();
              if ("bootstrapInit".equals(bsmName) && canFlatInit && "$$robo$init".equals(mName)
                  && "initializing".equals(name) && ("(L" + cls + ";)Ljava/lang/Object;").equals(descriptor)) {
                mv.visitInsn(Opcodes.POP);
                mv.visitFieldInsn(Opcodes.GETSTATIC, IDS, "NO_SHADOW", "Ljava/lang/Object;");
                modified[0] = true;
                stats.initFlat.incrementAndGet();
                return;
              }
              if (("bootstrap".equals(bsmName) || "bootstrapStatic".equals(bsmName))
                  && bsmArgs != null && bsmArgs.length == 2
                  && bsmArgs[0] instanceof Handle orig && bsmArgs[1] instanceof Integer isNative) {
                if (isNative == 0 && (!classShadowed
                    || (shadowedMethodNames != OPAQUE && !shadowedMethodNames.contains(name)))) {
                  int op = directOpcode(cls, bsmName, descriptor, orig);
                  if (op != -1) {
                    mv.visitMethodInsn(op, orig.getOwner(), orig.getName(), orig.getDesc(), orig.isInterface());
                    modified[0] = true;
                    stats.indyFlat.incrementAndGet();
                    return;
                  }
                }
                stats.indyKept.incrementAndGet();
              }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs);
          }
        };
      }
    }, 0);
    return modified[0] ? cw.toByteArray() : bytes;
  }

  private static int directOpcode(String cls, String bsmName, String indyDesc, Handle orig) {
    if (!cls.equals(orig.getOwner())) return -1;
    if ("bootstrapStatic".equals(bsmName)) {
      if (orig.getTag() == Opcodes.H_INVOKESTATIC && orig.getDesc().equals(indyDesc)) {
        return Opcodes.INVOKESTATIC;
      }
      return -1;
    }
    String expectedDesc = "(L" + cls + ";" + orig.getDesc().substring(1);
    if (!expectedDesc.equals(indyDesc)) return -1;
    return switch (orig.getTag()) {
      case Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
      case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
      case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
      default -> -1;
    };
  }

  /**
   * Recognizes Robolectric's exact 3-instruction {@code <clinit>}:
   * {@code LDC Type(cls); INVOKESTATIC RobolectricInternals.classInitializing(Class)V; RETURN}
   * with no exception handlers, and rewrites it to {@code INVOKESTATIC cls.__staticInitializer__()V; RETURN}.
   * Any other {@code <clinit>} is replayed verbatim via ASM's {@link org.objectweb.asm.tree.MethodNode}.
   */
  private static final class ClinitFlattener extends org.objectweb.asm.tree.MethodNode {
    private final String cls;
    private final MethodVisitor target;
    private final boolean[] modified;
    private final Stats stats;

    ClinitFlattener(String cls, MethodVisitor target, boolean[] modified, Stats stats) {
      super(ASM);
      this.cls = cls;
      this.target = target;
      this.modified = modified;
      this.stats = stats;
    }

    @Override
    public void visitEnd() {
      if (matchesRoboClinit()) {
        target.visitCode();
        target.visitMethodInsn(Opcodes.INVOKESTATIC, cls, "__staticInitializer__", "()V", false);
        target.visitInsn(Opcodes.RETURN);
        target.visitMaxs(Math.max(1, maxStack), maxLocals);
        target.visitEnd();
        modified[0] = true;
        stats.clinitFlat.incrementAndGet();
      } else {
        accept(target);
      }
    }

    private boolean matchesRoboClinit() {
      if (tryCatchBlocks != null && !tryCatchBlocks.isEmpty()) return false;
      List<org.objectweb.asm.tree.AbstractInsnNode> real = new ArrayList<>(4);
      for (var n = instructions.getFirst(); n != null; n = n.getNext()) {
        int type = n.getType();
        if (type == org.objectweb.asm.tree.AbstractInsnNode.LABEL
            || type == org.objectweb.asm.tree.AbstractInsnNode.LINE
            || type == org.objectweb.asm.tree.AbstractInsnNode.FRAME) {
          continue;
        }
        real.add(n);
        if (real.size() > 3) return false;
      }
      if (real.size() != 3) return false;
      if (!(real.get(0) instanceof org.objectweb.asm.tree.LdcInsnNode ldc)
          || !(ldc.cst instanceof Type t)
          || t.getSort() != Type.OBJECT
          || !cls.equals(t.getInternalName())) {
        return false;
      }
      if (!(real.get(1) instanceof org.objectweb.asm.tree.MethodInsnNode mi)
          || mi.getOpcode() != Opcodes.INVOKESTATIC
          || !RI.equals(mi.owner)
          || !"classInitializing".equals(mi.name)
          || !"(Ljava/lang/Class;)V".equals(mi.desc)) {
        return false;
      }
      return real.get(2).getOpcode() == Opcodes.RETURN;
    }
  }
}
