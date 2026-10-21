package library.server.net;

import library.common.Protocol;
import library.server.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * TÌM SERVER TRONG MẠNG LAN.
 * Client gửi gói UDP broadcast "LIBRARY_BOOKING_DISCOVER" tới cổng 5051 của mọi máy trong mạng;
 * luồng này trả lời "LIBRARY_BOOKING_SERVER;tên;cổng TCP;phiên bản" để client tự điền địa chỉ,
 * người dùng không cần biết IP của máy chủ.
 */
public class DiscoveryResponder implements Runnable {
    private final int udpPort;
    private final int tcpPort;
    private final String serverName;
    private volatile DatagramSocket socket;
    private volatile boolean running = true;

    public DiscoveryResponder(int udpPort, int tcpPort, String serverName) {
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.serverName = serverName.replace(";", ",");
    }

    @Override
    public void run() {
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(udpPort));
            socket = s;
            Log.info("Đang lắng nghe yêu cầu tìm server trong LAN (UDP %d)", udpPort);
            byte[] buffer = new byte[512];
            byte[] reply = (Protocol.DISCOVER_REPLY + ";" + serverName + ";" + tcpPort + ";" + Protocol.VERSION)
                    .getBytes(StandardCharsets.UTF_8);
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                s.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!msg.startsWith(Protocol.DISCOVER_REQUEST)) {
                    continue;
                }
                s.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                Log.info("Trả lời yêu cầu tìm server từ %s", packet.getAddress().getHostAddress());
            }
        } catch (IOException e) {
            if (running) {
                Log.warn("Không mở được cổng UDP %d cho tìm kiếm LAN: %s (client vẫn có thể nhập IP thủ công)",
                        udpPort, e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
    }
}
