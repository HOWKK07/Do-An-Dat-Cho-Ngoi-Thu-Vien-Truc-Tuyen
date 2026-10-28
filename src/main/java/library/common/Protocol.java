package library.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * Các hằng số dùng chung cho client và server: cổng mạng, quy định đặt chỗ,
 * và bộ lọc an toàn khi giải tuần tự hóa (deserialize) dữ liệu nhận qua mạng.
 */
public final class Protocol {

    /** Tên nhóm thực hiện đề tài (dòng "Nhóm ..." ở màn hình đăng nhập, file SQL). */
    public static final String GROUP_NAME = "Fix Bug";
    /** Tên hiệu của thư viện trên giao diện. */
    public static final String BRAND = "Fixbug";
    public static final String LIBRARY_NAME = "Thư viện " + BRAND;
    public static final String APP_NAME = LIBRARY_NAME + " - Đặt chỗ học trực tuyến";
    /** Tên server mặc định (hiện khi client tìm server trong LAN, nhật ký, cửa sổ máy chủ); đổi bằng --name. */
    public static final String SERVER_NAME = BRAND;
    public static final String VERSION = "1.0";

    /** Cổng TCP mặc định của server. */
    public static final int DEFAULT_PORT = 5050;
    /** Cổng UDP để client tìm server trong mạng LAN (broadcast). */
    public static final int DISCOVERY_PORT = 5051;
    public static final String DISCOVER_REQUEST = "LIBRARY_BOOKING_DISCOVER";
    public static final String DISCOVER_REPLY = "LIBRARY_BOOKING_SERVER";

    /** Client gửi PING mỗi 15 giây để giữ kết nối và đo độ trễ. */
    public static final int HEARTBEAT_SECONDS = 15;
    /** Server đóng kết nối nếu không nhận được gói nào trong 45 giây (= 3 nhịp heartbeat). */
    public static final int SERVER_READ_TIMEOUT_SECONDS = 45;

    /** Quy định đặt chỗ. */
    public static final int MAX_BOOKINGS_PER_DAY = 3;
    /** Thư viện chỉ cho đặt trước tối đa 3 ngày (tính cả hôm nay) - cần 100 điểm uy tín, xem {@link Reputation}. */
    public static final int BOOKING_DAYS_AHEAD = 3;
    /** Quản trị viên không giới hạn số lượt nhưng cũng chỉ đặt trước tối đa 3 ngày như quy định của thư viện. */
    public static final int ADMIN_BOOKING_DAYS_AHEAD = BOOKING_DAYS_AHEAD;
    public static final int CHECKIN_EARLY_MINUTES = 15;
    public static final int CHECKIN_LATE_MINUTES = 15;

    /**
     * Chỉ cho phép giải tuần tự hóa các lớp của ứng dụng và lớp chuẩn của JDK.
     * Ngăn tấn công deserialization khi có máy lạ trong LAN gửi dữ liệu độc hại.
     */
    private static final ObjectInputFilter NETWORK_FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=20;maxarray=100000;library.common.*;java.base/*;!*");

    private Protocol() {
    }

    public static ObjectInputStream newNetworkInput(InputStream in) throws IOException {
        ObjectInputStream ois = new ObjectInputStream(in);
        ois.setObjectInputFilter(NETWORK_FILTER);
        return ois;
    }
}
