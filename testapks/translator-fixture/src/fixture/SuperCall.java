package fixture;

/**
 * A super call to a default method of an indirect superinterface: ART allows it, HotSpot refuses an invokespecial of
 * an interface method not named by a direct superinterface. javac only writes the legal form (Mid.super.who()), so
 * build.sh retargets that call to Base (RetargetSuper) before dexing, the shape R8 leaves after merging interfaces.
 */
public final class SuperCall {
  private SuperCall() {}

  public interface Base {
    default String who() {
      return "base";
    }
  }

  public interface Mid extends Base {}

  public static final class Impl implements Mid {
    @Override
    public String who() {
      return "impl+" + Mid.super.who();
    }
  }
}
