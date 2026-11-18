package dev.apksim;

import android.os.Handler;
import android.os.Looper;

/**
 * The Java side of the NDK's ALooper for loaded ELF code (native/alooper.c, issue 477). On a device a thread's Java
 * Looper and its native ALooper are one object, so fd callbacks a library adds with ALooper_addFd on the main thread
 * (or a HandlerThread) run there while the Java Looper loops. Here the native side's watcher thread polls those fds and,
 * when one is ready, {@link #post}s an asynchronous message (as a device's native events are not held by sync barriers)
 * that runs the ready callbacks on that thread through {@link #pump}. Only called from native code.
 */
final class NativeLoopers {
  private NativeLoopers() {}

  /** The calling thread's Java Looper, or null. */
  static Object myLooper() {
    return Looper.myLooper();
  }

  /** False when the looper has quit. */
  static boolean post(Object looper, long nativeLooper, int token) {
    return Handler.createAsync((Looper) looper).post(() -> pump(nativeLooper, token));
  }

  /** Runs the callbacks of the ready fds on this (the looper's) thread; registered by alooper_init. */
  private static native void pump(long nativeLooper, int token);
}
