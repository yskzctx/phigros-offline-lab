package android.util;

/**
 * JVM stand-in for android.util.Log.
 *
 * android.jar ships stub methods that throw RuntimeException("Stub!"), so any class that
 * logs cannot be exercised off-device. This minimal shim is compiled into the test
 * classpath ahead of android.jar so SaveTransfer's file logic can run in a plain JVM.
 */
public final class Log {
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int v(String tag, String message) { return 0; }
    private Log() {}
}
