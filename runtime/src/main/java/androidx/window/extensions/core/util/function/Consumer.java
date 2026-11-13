package androidx.window.extensions.core.util.function;

/**
 * The library's own Consumer (androidx.window.extensions.core, which apps bundle): the same interface, here so the
 * stand-in can name it in APKs that do not have it. apksim.jar is ahead of the app's classes, so this one is used.
 */
@FunctionalInterface
public interface Consumer<T> {
  void accept(T t);
}
