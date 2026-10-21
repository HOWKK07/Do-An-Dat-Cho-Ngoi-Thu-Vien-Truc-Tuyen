package library.client.net;

import library.common.Protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tìm server trong mạng LAN bằng UDP broadcast. */
public final class ServerDiscovery {

    /** Một server tìm thấy. */
    public static final class ServerInfo {
        private final String name;
        private final String host;
        private final int port;
        private final String version;

        ServerInfo(String name, String host, int port, String version) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return name + "   —   " + host + ":" + port;
        }
    }

    private ServerDiscovery() {
    }

    public static List<ServerInfo> discover(int discoveryPort, int timeoutMs) {
        Map<String, ServerInfo> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(200);
            byte[] payload = Protocol.DISCOVER_REQUEST.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : broadcastTargets()) {
                try {
                    socket.send(new DatagramPacket(payload, payload.length, target, discoveryPort));
                } catch (IOException ignored) {
                    // một số card mạng không cho broadcast - bỏ qua
                }
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                String[] parts = msg.split(";");
                if (parts.length < 3 || !Protocol.DISCOVER_REPLY.equals(parts[0])) {
                    continue;
                }
                try {
                    int port = Integer.parseInt(parts[2].trim());
                    InetAddress from = p.getAddress();
                    ServerInfo info = new ServerInfo(parts[1], from.getHostAddress(), port,
                            parts.length > 3 ? parts[3] : "?");
                    String key = parts[1] + ":" + port;
                    ServerInfo existing = found.get(key);
                    // Cùng một server trả lời qua nhiều đường: ưu tiên địa chỉ LAN thay vì 127.0.0.1
                    if (existing == null || (existing.host.startsWith("127.") && !from.isLoopbackAddress())) {
                        found.put(key, info);
                    }
                } catch (NumberFormatException ignored) {
                    // gói tin lỗi
                }
            }
        } catch (IOException ignored) {
            // trả về những gì đã tìm được
        }
        return new ArrayList<>(found.values());
    }

    private static Set<InetAddress> broadcastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) {
                        targets.add(ia.getBroadcast()); // VD: 192.168.1.255
                    }
                }
            }
        } catch (IOException ignored) {
            // bỏ qua
        }
        targets.add(InetAddress.getLoopbackAddress()); // server chạy cùng máy
        return targets;
    }
}
