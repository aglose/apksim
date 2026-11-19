package fixture;

/**
 * SuperCall2's shape with the superclass and its interface in another DEX file (R8 spreads a minified app over several):
 * build.sh retargets Impl's call of J.super.who() to MultiLib.Base (RetargetSuper) and dexes MultiLib on its own.
 */
public final class MultiImpl {
  private MultiImpl() {}

  public interface J {
    default String who() {
      return "j";
    }
  }

  public static final class Impl extends MultiLib.S implements J {
    @Override
    public String who() {
      return "impl+" + J.super.who();
    }
  }

  public static String run() {
    String got = new Impl().who();
    return got.equals("impl+base") ? "" : "super call through a superclass in another DEX: expected impl+base, got " + got + "\n";
  }
}
