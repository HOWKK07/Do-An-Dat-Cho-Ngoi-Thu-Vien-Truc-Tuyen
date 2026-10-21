package library.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Ghi log đơn giản cho server. Mỗi dòng có kèm TÊN LUỒNG đang chạy,
 * giúp quan sát rõ việc phân luồng (client-handler-1, scheduler-1, event-broadcaster-1...).
 */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private Log() {
    }

    public static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static void info(String format, Object... args) {
        write("INFO", format(format, args), null);
    }

    public static void warn(String format, Object... args) {
        write("WARN", format(format, args), null);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    private static String format(String format, Object[] args) {
        return args == null || args.length == 0 ? format : String.format(format, args);
    }

    private static void write(String level, String message, Throwable t) {
        String line = String.format("%s %-5s [%s] %s", LocalTime.now().format(TIME), level,
                Thread.currentThread().getName(), message);
        if (t != null) {
            line += " | " + t;
        }
        if ("ERROR".equals(level)) {
            System.err.println(line);
            if (t != null && !(t instanceof java.io.IOException)) {
                t.printStackTrace();
            }
        } else {
            System.out.println(line);
        }
        for (Consumer<String> l : LISTENERS) {
            try {
                l.accept(line);
            } catch (RuntimeException ignored) {
                // không để lỗi giao diện làm hỏng luồng nghiệp vụ
            }
        }
    }
}
