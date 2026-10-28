package library.server.thread;

import library.server.Log;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * QUẢN LÝ LUỒNG của server - tách công việc thành các nhóm luồng riêng biệt:
 *
 * <pre>
 *  accept-loop (1 luồng)        : chỉ nhận kết nối mới rồi giao cho pool  →  không bao giờ bị nghẽn
 *  client-handler-N (pool)      : mỗi client một luồng đọc/xử lý yêu cầu; tối đa maxClients luồng,
 *                                 luồng rảnh quá 60s tự giải phóng; vượt giới hạn → từ chối lịch sự
 *  scheduler-N (2 luồng)        : tác vụ nền. Dọn phiên hết hạn (30 giây/lần, chỉ duyệt bộ nhớ);
 *                                 HẸN GIỜ THEO MỐC cho lượt đặt: chỉ thức dậy 10 lần/ngày (15 phút sau
 *                                 giờ bắt đầu và lúc kết thúc mỗi ca) thay vì quét CSDL 30 giây/lần;
 *                                 mở khóa điểm uy tín: 1 lần lúc 0 giờ
 *  event-broadcaster (1 luồng)  : đẩy sự kiện tới các client, không làm chậm luồng xử lý yêu cầu
 *  (pool kết nối MySQL)         : các luồng client-handler mượn / trả kết nối CSDL dùng chung - xem ConnectionPool
 *  lan-discovery (1 luồng)      : trả lời gói UDP tìm server trong LAN
 * </pre>
 */
public class ServerThreadPools {
    public static final int CORE_CLIENT_THREADS = 4;
    /** Mỗi lần "ngủ" tối đa 30 phút rồi tự tính lại mốc - phòng khi đồng hồ máy bị chỉnh giờ. */
    public static final long MAX_SLEEP_MINUTES = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM");

    private final List<MilestoneTask> milestoneTasks = new CopyOnWriteArrayList<>();

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

    /**
     * Chạy {@code task} MỘT LẦN sau {@code delayMs} (VD nhắc người đặt sau 30 phút). Chỉ là một mục trong hàng đợi của
     * luồng scheduler - không tạo luồng mới, không tốn CPU trong lúc chờ.
     */
    public void scheduleOnce(String name, Runnable task, long delayMs) {
        submit(safe(name, task), Math.max(0, delayMs));
    }

    /**
     * LẬP LỊCH THEO MỐC THỜI GIAN (thay cho quét định kỳ): chạy {@code task} một lần ngay (bù phần việc trong lúc
     * server tắt), sau đó luồng scheduler "ngủ" tới mốc kế tiếp do {@code nextRun} tính ra rồi mới chạy lại.
     * Giữa hai mốc không tốn CPU hay truy vấn CSDL nào, và không cần luồng / bộ hẹn giờ riêng cho từng lượt đặt.
     *
     * @param label   mô tả ngắn để ghi log và hiển thị thống kê
     * @param nextRun nhận thời điểm hiện tại, trả về mốc chạy kế tiếp (phải sau thời điểm đó)
     */
    public MilestoneTask scheduleAtMilestones(String label, Runnable task, UnaryOperator<LocalDateTime> nextRun) {
        MilestoneTask t = new MilestoneTask(label, task, nextRun);
        milestoneTasks.add(t);
        submit(t, 0);
        return t;
    }

    private void submit(Runnable r, long delayMs) {
        try {
            scheduler.schedule(r, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // server đang tắt - không hẹn giờ nữa
        }
    }

    /** Một tác vụ chạy theo mốc thời gian - xem {@link #scheduleAtMilestones}. */
    public final class MilestoneTask implements Runnable {
        private final String label;
        private final Runnable task;
        private final UnaryOperator<LocalDateTime> nextRun;
        private final AtomicLong runs = new AtomicLong();
        private volatile LocalDateTime next; // null = chạy ngay

        private MilestoneTask(String label, Runnable task, UnaryOperator<LocalDateTime> nextRun) {
            this.label = label;
            this.task = task;
            this.nextRun = nextRun;
        }

        @Override
        public void run() {
            if (next == null || !LocalDateTime.now().isBefore(next)) {
                safe(label, task).run();
                runs.incrementAndGet();
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime n = null;
                try {
                    n = nextRun.apply(now);
                } catch (RuntimeException e) {
                    Log.error("Không tính được mốc kế tiếp cho '" + label + "'", e);
                }
                next = n != null && n.isAfter(now) ? n : now.plusMinutes(MAX_SLEEP_MINUTES);
                Log.info("Hẹn giờ: đã %s - lần tiếp theo lúc %s", label, TIME.format(next));
            }
            // Ngủ tới mốc (tối đa MAX_SLEEP_MINUTES); thức dậy sớm do giới hạn này thì chỉ tính lại, không chạy tác vụ
            long ms = Duration.between(LocalDateTime.now(), next).toMillis();
            submit(this, Math.max(0, Math.min(ms, TimeUnit.MINUTES.toMillis(MAX_SLEEP_MINUTES))));
        }

        public String getLabel() {
            return label;
        }

        public LocalDateTime getNext() {
            return next;
        }

        public long getRuns() {
            return runs.get();
        }
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
        for (MilestoneTask t : milestoneTasks) {
            LocalDateTime n = t.getNext();
            m.put("Hẹn giờ: " + t.getLabel(), (n == null ? "đang chạy" : "lần tới " + TIME.format(n))
                    + " (đã chạy " + t.getRuns() + " lần)");
        }
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
