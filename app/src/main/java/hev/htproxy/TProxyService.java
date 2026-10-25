package hev.htproxy;

/**
 * JNI binding for the pinned hev-socks5-tunnel native library.
 * The JNI symbols are registered against this exact package/class name.
 */
public final class TProxyService {
    private TProxyService() {}

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
