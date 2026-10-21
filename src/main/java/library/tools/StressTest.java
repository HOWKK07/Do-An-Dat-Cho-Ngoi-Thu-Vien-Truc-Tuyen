package library.tools;

import library.client.net.ServerConnection;
import library.common.Booking;
import library.common.Command;
import library.common.Message;
import library.common.SeatMap;
import library.common.SeatState;
import library.common.SeatView;
import library.common.TimeSlot;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * KIỂM THỬ ĐỒNG THỜI (minh họa phần quản lý luồng).
 * N client đăng nhập song song, rồi CÙNG MỘT LÚC đặt CÙNG MỘT chỗ.
 * Kết quả đúng: đúng 1 người thành công, N-1 người nhận thông báo "chỗ vừa được người khác đặt".
 *
 * <pre>java -cp FixBug-Server.jar library.tools.StressTest [host] [port] [số client]</pre>
 */
public final class StressTest {

    private StressTest() {
    }

    private static final class Result {
        final String user;
        final boolean ok;
        final String text;
        final long micros;
        final Booking booking;

        Result(String user, boolean ok, String text, long micros, Booking booking) {
            this.user = user;
            this.ok = ok;
            this.text = text;
            this.micros = micros;
            this.booking = booking;
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 20;

        System.out.println("=== KIỂM THỬ ĐẶT CHỖ ĐỒNG THỜI: " + n + " client -> " + host + ":" + port + " ===");
        ExecutorService pool = Executors.newFixedThreadPool(n);

        // 1. Đăng nhập song song N tài khoản test (tự đăng ký nếu chưa có)
        List<Future<ServerConnection>> logins = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            final int idx = i;
            logins.add(pool.submit(() -> loginOrRegister(host, port, idx)));
        }
        List<ServerConnection> conns = new ArrayList<>();
        for (Future<ServerConnection> f : logins) {
            conns.add(f.get());
        }
        System.out.println("[1] " + conns.size() + " client đã đăng nhập.");

        // 2. Chọn một chỗ đang trống: ngày mai, ca 1
        LocalDate date = LocalDate.now().plusDays(1);
        int slot = TimeSlot.ALL.get(0).getIndex();
        SeatMap map = conns.get(0).sendAndWait(Message.request(Command.GET_SEAT_MAP)
                .put("date", date).put("slot", slot)).get("map");
        String seatId = null;
        for (SeatView v : map.getSeats()) {
            if (v.getState() == SeatState.AVAILABLE) {
                seatId = v.getSeat().getId();
                break;
            }
        }
        if (seatId == null) {
            System.out.println("Không còn chỗ trống để thử.");
            System.exit(1);
        }
        System.out.println("[2] Mục tiêu: chỗ " + seatId + ", " + date + ", ca " + slot);

        // 3. Tất cả cùng bấm "đặt" tại một thời điểm
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        final String target = seatId;
        for (int i = 0; i < n; i++) {
            ServerConnection c = conns.get(i);
            String user = String.format("test%02d", i + 1);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                long t0 = System.nanoTime();
                Message r = c.sendAndWait(Message.request(Command.BOOK_SEAT)
                        .put("seatId", target).put("date", date).put("slot", slot));
                long us = (System.nanoTime() - t0) / 1000;
                return new Result(user, r.isSuccess(), r.getText().split("\n")[0], us, r.get("booking"));
            }));
        }
        ready.await();
        System.out.println("[3] " + n + " luồng sẵn sàng - BẮT ĐẦU!");
        go.countDown();

        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get());
        }
        Collections.sort(results, (a, b) -> Long.compare(a.micros, b.micros));
        int success = 0;
        Result winner = null;
        for (Result r : results) {
            System.out.printf("    %-7s %-9s %6.1f ms  %s%n", r.user, r.ok ? "THÀNH CÔNG" : "thất bại",
                    r.micros / 1000.0, r.text);
            if (r.ok) {
                success++;
                winner = r;
            }
        }
        System.out.println();
        System.out.println("[4] KẾT QUẢ: " + success + "/" + n + " yêu cầu thành công  →  "
                + (success == 1 ? "ĐÚNG (không đặt trùng chỗ)" : "SAI!"));

        // 5. Dọn dẹp: hủy lượt đặt vừa tạo, đăng xuất
        if (winner != null && winner.booking != null) {
            int idx = Integer.parseInt(winner.user.substring(4)) - 1;
            Message r = conns.get(idx).sendAndWait(Message.request(Command.CANCEL_BOOKING)
                    .put("bookingId", winner.booking.getId()));
            System.out.println("[5] Dọn dẹp: " + r.getText());
        }
        for (ServerConnection c : conns) {
            c.logout();
        }
        pool.shutdown();
        System.exit(success == 1 ? 0 : 2);
    }

    private static ServerConnection loginOrRegister(String host, int port, int idx) throws IOException {
        String username = String.format("test%02d", idx);
        ServerConnection c = new ServerConnection(host, port);
        c.connect();
        Message r = c.sendAndWait(Message.request(Command.LOGIN).put("username", username).put("password", "123456"));
        if (!r.isSuccess()) {
            Message reg = c.sendAndWait(Message.request(Command.REGISTER).put("username", username)
                    .put("password", "123456").put("fullName", "Tài khoản thử " + idx)
                    .put("studentId", String.format("99000%03d", idx)));
            if (!reg.isSuccess()) {
                throw new IOException(username + ": " + reg.getText());
            }
            r = c.sendAndWait(Message.request(Command.LOGIN).put("username", username).put("password", "123456"));
            if (!r.isSuccess()) {
                throw new IOException(username + ": " + r.getText());
            }
        }
        c.setSessionToken(r.getString("token"));
        return c;
    }
}
