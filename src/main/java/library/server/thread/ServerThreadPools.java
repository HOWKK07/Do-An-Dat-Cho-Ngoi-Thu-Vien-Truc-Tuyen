package library.server.thread;

import library.server.Log;

import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * QUẢN LÝ LUỒNG của server - tách công việc thành các nhóm luồng riêng biệt:
 *
 * <pre>
 *  accept-loop (1 luồng)        : chỉ nhận kết nối mới rồi giao cho pool  →  không bao giờ bị nghẽn
 *  client-handler-N (pool)      : mỗi client một luồng đọc/xử lý yêu cầu; tối đa maxClients luồng,
 *                                 luồng rảnh quá 60s tự giải phóng; vượt giới hạn → từ chối lịch sự
 *  scheduler-N (2 luồng)        : tác vụ định kỳ: dọn phiên hết hạn, tự hủy lượt không check-in
 *  event-broadcaster (1 luồng)  : đẩy sự kiện tới các client, không làm chậm luồng xử lý yêu cầu
 *  (pool kết nối MySQL)         : các luồng client-handler mượn / trả kết nối CSDL dùng chung - xem ConnectionPool
 *  lan-discovery (1 luồng)      : trả lời gói UDP tìm server trong LAN
 * </pre>
 */
public class ServerThreadPools {
    public static final int CORE_CLIENT_THREADS = 4;

    private final ThreadPoolExecutor clientPool;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService eventExecutor;

    public ServerThreadPools(int maxClients) {
        // SynchronousQueue: không xếp hàng - có luồng rảnh thì nhận ngay, không có thì tạo luồng mới,
        // đạt tối đa thì ném RejectedExecutionException để server trả lời "máy chủ đang quá tải".
        clientPool = new ThreadPoolExecutor(
                Math.min(CORE_CLIENT_THREADS, maxClients), maxClients,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new NamedThreadFactory("client-handler", true),
                new ThreadPoolExecutor.AbortPolicy());

        scheduler = new ScheduledThreadPoolExecutor(2, new NamedThreadFactory("scheduler", true));
        scheduler.setRemoveOnCancelPolicy(true);

        eventExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("event-broadcaster", true));
    }

    /** Bọc tác vụ định kỳ: nếu tác vụ ném lỗi, ScheduledExecutor sẽ âm thầm dừng lặp - nên phải bắt lỗi. */
    public static Runnable safe(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.error("Tác vụ nền '" + name + "' gặp lỗi", t);
            }
        };
    }

    public void scheduleRepeating(String name, Runnable task, long initialDelaySec, long periodSec) {
        scheduler.scheduleWithFixedDelay(safe(name, task), initialDelaySec, periodSec, TimeUnit.SECONDS);
    }

    public ThreadPoolExecutor getClientPool() {
        return clientPool;
    }

    public ExecutorService getEventExecutor() {
        return eventExecutor;
    }

    public LinkedHashMap<String, String> stats() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("Pool client: đang xử lý / số luồng", clientPool.getActiveCount() + " / " + clientPool.getPoolSize());
        m.put("Pool client: tối đa cho phép", String.valueOf(clientPool.getMaximumPoolSize()));
        m.put("Pool client: số luồng cao nhất", String.valueOf(clientPool.getLargestPoolSize()));
        m.put("Pool client: kết nối đã phục vụ xong", String.valueOf(clientPool.getCompletedTaskCount()));
        m.put("Scheduler: lượt tác vụ nền đã chạy", String.valueOf(scheduler.getCompletedTaskCount()));
        return m;
    }

    public void shutdown() {
        clientPool.shutdownNow();
        scheduler.shutdownNow();
        eventExecutor.shutdown();
        try {
            eventExecutor.awaitTermination(2, TimeUnit.SECONDS);
            clientPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
