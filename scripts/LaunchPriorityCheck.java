import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Runs the runtime's manifest reading (dev.apksim.AppInfo.load, package-private, so by reflection) on an APK and checks
 * which launcher it picks. Run by scripts/launch-priority-check.sh with work/apksim.jar on the class path.
 */
public class LaunchPriorityCheck {
  static final String EXPECTED = "dev.apksim.launchpriority.High";

  public static void main(String[] args) throws Exception {
    Path apk = Path.of(args[0]);
    Path cache = Path.of(args[1]);
    Class<?> appInfo = Class.forName("dev.apksim.AppInfo");
    Method load = appInfo.getDeclaredMethod("load", Path.class, Path.class);
    load.setAccessible(true);
    System.clearProperty("apksim.launcher");
    load.invoke(null, apk, cache);
    String launcher = System.getProperty("apksim.launcher");
    if (!EXPECTED.equals(launcher)) {
      System.err.println("launcher is " + launcher + ", expected " + EXPECTED);
      System.exit(1);
    }
    System.out.println("launcher " + launcher + ": ok");
  }
}
