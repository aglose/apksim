package fixture;

/** The superclass half of MultiImpl's shape: kept in a different DEX file from the class that calls through it. */
public final class MultiLib {
  private MultiLib() {}

  public interface Base {
    default String who() {
      return "base";
    }
  }

  public static class S implements Base {}
}
