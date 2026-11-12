package fixture;

/**
 * A super call to a default method that the superclass's interface supplies, the class itself implementing only an
 * unrelated interface (R8 shape): build.sh retargets Impl's call of J.super.who() to Base (RetargetSuper).
 */
public final class SuperCall2 {
  private SuperCall2() {}

  public interface Base {
    default String who() {
      return "base";
    }
  }

  public interface J {
    default String who() {
      return "j";
    }
  }

  public static class S implements Base {}

  public static final class Impl extends S implements J {
    @Override
    public String who() {
      return "impl+" + J.super.who();
    }
  }
}
