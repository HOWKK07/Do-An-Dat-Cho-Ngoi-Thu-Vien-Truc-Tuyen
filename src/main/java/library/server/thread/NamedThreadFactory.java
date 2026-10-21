package library.server.thread;

import library.server.Log;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Tạo luồng có tên dễ đọc (client-handler-1, scheduler-2...) và ghi log khi luồng gặp lỗi bất ngờ. */
public class NamedThreadFactory implements ThreadFactory {
    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger counter = new AtomicInteger();

    public NamedThreadFactory(String prefix, boolean daemon) {
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
        t.setDaemon(daemon);
        t.setUncaughtExceptionHandler((th, ex) -> Log.error("Lỗi không mong muốn trong luồng " + th.getName(), ex));
        return t;
    }
}
