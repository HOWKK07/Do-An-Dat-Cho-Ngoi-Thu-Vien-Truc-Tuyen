package library.server;

import library.common.Protocol;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Cấu hình server, đọc từ tham số dòng lệnh. */
public class ServerConfig {
    private int port = Protocol.DEFAULT_PORT;
    private int discoveryPort = Protocol.DISCOVERY_PORT;
    private boolean discoveryEnabled = true;
    private int maxClients = 100;
    private int sessionTimeoutMinutes = 30;
    private int reconnectGraceSeconds = 120;
    private Path dbConfigFile = Paths.get(library.server.db.DbConfig.DEFAULT_FILE);
    private String serverName;
    private boolean noGui;
    private boolean help;

    public static ServerConfig parse(String[] args) {
        ServerConfig c = new ServerConfig();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--port":
                    c.port = intArg(args, ++i, a, 1, 65535);
                    break;
                case "--discovery-port":
                    c.discoveryPort = intArg(args, ++i, a, 1, 65535);
                    break;
                case "--no-discovery":
                    c.discoveryEnabled = false;
                    break;
                case "--max-clients":
                    c.maxClients = intArg(args, ++i, a, 1, 2000);
                    break;
                case "--session-timeout":
                    c.sessionTimeoutMinutes = intArg(args, ++i, a, 1, 24 * 60);
                    break;
                case "--reconnect-grace":
                    c.reconnectGraceSeconds = intArg(args, ++i, a, 5, 3600);
                    break;
                case "--db-config":
                    c.dbConfigFile = Paths.get(strArg(args, ++i, a));
                    break;
                case "--name":
                    c.serverName = strArg(args, ++i, a);
                    break;
                case "--nogui":
                    c.noGui = true;
                    break;
                case "-h":
                case "--help":
                    c.help = true;
                    break;
                default:
                    throw new IllegalArgumentException("Tham số không hợp lệ: " + a);
            }
        }
        if (c.serverName == null) {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "server";
            }
            c.serverName = Protocol.LIBRARY_NAME + " (" + host + ")";
        }
        return c;
    }

    public static String usage() {
        return "Cách dùng: java -jar FixBug-Server.jar [tùy chọn]\n"
                + "  --port N              Cổng TCP (mặc định " + Protocol.DEFAULT_PORT + ")\n"
                + "  --discovery-port N    Cổng UDP tìm server trong LAN (mặc định " + Protocol.DISCOVERY_PORT + ")\n"
                + "  --no-discovery        Tắt tính năng tìm server trong LAN\n"
                + "  --max-clients N       Số client tối đa phục vụ đồng thời (mặc định 100)\n"
                + "  --session-timeout N   Phút không thao tác thì hết phiên (mặc định 30)\n"
                + "  --reconnect-grace N   Giây giữ phiên khi client rớt mạng (mặc định 120)\n"
                + "  --db-config FILE      File cấu hình MySQL (mặc định ./db.properties)\n"
                + "  --name TÊN            Tên server hiển thị khi client tìm trong LAN\n"
                + "  --nogui               Chạy dạng dòng lệnh, không mở cửa sổ giám sát\n";
    }

    private static int intArg(String[] args, int i, String name, int min, int max) {
        String v = strArg(args, i, name);
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw new IllegalArgumentException(name + " phải trong khoảng " + min + " - " + max);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " cần một số nguyên, nhận được: " + v);
        }
    }

    private static String strArg(String[] args, int i, String name) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Thiếu giá trị cho " + name);
        }
        return args[i];
    }

    public int getPort() {
        return port;
    }

    public int getDiscoveryPort() {
        return discoveryPort;
    }

    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    public int getMaxClients() {
        return maxClients;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public int getReconnectGraceSeconds() {
        return reconnectGraceSeconds;
    }

    public Path getDbConfigFile() {
        return dbConfigFile;
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isNoGui() {
        return noGui;
    }

    public boolean isHelp() {
        return help;
    }
}
