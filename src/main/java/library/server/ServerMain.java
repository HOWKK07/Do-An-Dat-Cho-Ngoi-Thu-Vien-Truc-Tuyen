package library.server;

import library.common.Fmt;
import library.common.SessionInfo;
import library.server.ui.ServerFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Map;

/** Điểm khởi động server. Mặc định mở cửa sổ giám sát; dùng --nogui để chạy dạng dòng lệnh. */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) {
        ServerConfig config;
        try {
            config = ServerConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(ServerConfig.usage());
            System.exit(1);
            return;
        }
        if (config.isHelp()) {
            System.out.println(ServerConfig.usage());
            return;
        }

        LibraryServer server;
        try {
            server = new LibraryServer(config);
        } catch (IOException | RuntimeException e) {
            System.err.println("Không đọc được cấu hình CSDL: " + e.getMessage());
            System.exit(1);
            return;
        }
        boolean gui = !config.isNoGui() && !GraphicsEnvironment.isHeadless();
        if (gui) {
            try {
                SwingUtilities.invokeAndWait(() -> new ServerFrame(server).setVisible(true));
            } catch (Exception e) {
                gui = false;
                System.err.println("Không mở được giao diện, chuyển sang chế độ dòng lệnh: " + e);
            }
        }

        try {
            server.start();
        } catch (IOException e) {
            String msg = e instanceof java.net.BindException
                    ? "Không thể mở cổng " + config.getPort() + ": " + e.getMessage()
                    + "\nCó thể một server khác đang chạy. Hãy tắt nó hoặc dùng --port khác."
                    : "Không thể khởi động server:\n" + e.getMessage();
            Log.error(msg, null);
            if (gui) {
                JOptionPane.showMessageDialog(null, msg, "Lỗi khởi động", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown-hook"));

        if (!gui) {
            runConsole(server);
        }
    }

    /** Lệnh quản trị đơn giản khi chạy không có giao diện. */
    private static void runConsole(LibraryServer server) {
        Thread t = new Thread(() -> {
            System.out.println("Gõ 'help' để xem các lệnh quản trị.");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 2);
                    switch (parts[0].toLowerCase()) {
                        case "":
                            break;
                        case "help":
                            System.out.println("  stats          Thống kê server, luồng, phiên");
                            System.out.println("  sessions       Danh sách phiên đăng nhập");
                            System.out.println("  kick <mã>      Ngắt phiên (VD: kick S0003)");
                            System.out.println("  stop           Dừng server");
                            break;
                        case "stats":
                            for (Map.Entry<String, String> e : server.getStats().entrySet()) {
                                System.out.printf("  %-42s %s%n", e.getKey(), e.getValue());
                            }
                            break;
                        case "sessions":
                            for (SessionInfo s : server.getSessions().snapshot()) {
                                System.out.printf("  %s  %-10s %-24s %-15s %-12s hoạt động cuối %s%n", s.getId(),
                                        s.getUsername(), s.getFullName(), s.getIp(),
                                        s.isOnline() ? "online" : "chờ kết nối", Fmt.dateTime(s.getLastActive()));
                            }
                            break;
                        case "kick":
                            boolean ok = parts.length > 1 && server.getSessions().kick(parts[1].trim().toUpperCase(),
                                    "Quản trị viên máy chủ đã kết thúc phiên đăng nhập của bạn.");
                            System.out.println(ok ? "  Đã ngắt phiên." : "  Không tìm thấy phiên.");
                            break;
                        case "stop":
                        case "exit":
                            System.exit(0);
                            return;
                        default:
                            System.out.println("  Lệnh không hợp lệ. Gõ 'help'.");
                    }
                }
            } catch (IOException ignored) {
                // không có bàn phím (chạy nền) - bỏ qua
            }
        }, "console");
        t.setDaemon(true);
        t.start();
    }
}
