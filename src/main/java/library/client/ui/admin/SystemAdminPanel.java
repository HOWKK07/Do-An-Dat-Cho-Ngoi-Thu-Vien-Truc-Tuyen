package library.client.ui.admin;

import library.client.ui.ClientContext;
import library.client.ui.ListTableModel;
import library.client.ui.Toast;
import library.client.ui.UiKit;
import library.common.Command;
import library.common.Message;
import library.common.SessionInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Giám sát hệ thống từ xa: phiên đăng nhập (ngắt phiên) và thống kê luồng / tài nguyên server. */
public class SystemAdminPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final ListTableModel<SessionInfo> sessionModel = new ListTableModel<SessionInfo>()
            .col("Mã phiên", SessionInfo::getId)
            .col("Tài khoản", SessionInfo::getUsername)
            .col("Họ tên", SessionInfo::getFullName)
            .col("Vai trò", SessionInfo::getRole)
            .col("Địa chỉ IP", SessionInfo::getIp)
            .col("Trạng thái", SessionInfo::getStatusLabel)
            .col("Đăng nhập lúc", LocalDateTime.class, SessionInfo::getLoginAt)
            .col("Hoạt động cuối", LocalDateTime.class, SessionInfo::getLastActive);
    private final ListTableModel<Map.Entry<String, String>> statsModel = new ListTableModel<Map.Entry<String, String>>()
            .col("Chỉ số", Map.Entry::getKey)
            .col("Giá trị", Map.Entry::getValue);
    private final JTable sessionTable = UiKit.table(sessionModel);
    private final JTable statsTable = UiKit.table(statsModel);
    private final JLabel updated = UiKit.label(" ", Font.PLAIN, 12f, UiKit.MUTED);

    public SystemAdminPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(10, 10, 10, 10));

        sessionTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                if (!sel) {
                    setForeground(String.valueOf(v).startsWith("Trực") ? UiKit.SUCCESS : UiKit.WARNING);
                }
                return this;
            }
        });
        statsTable.setAutoCreateRowSorter(false);
        statsTable.getColumnModel().getColumn(0).setPreferredWidth(260);

        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> reload());
        JButton kick = new JButton("Ngắt phiên đã chọn");
        kick.addActionListener(e -> kickSelected());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(refresh);
        bar.add(kick);
        bar.add(updated);

        JPanel sessions = new JPanel(new BorderLayout());
        sessions.setBorder(BorderFactory.createTitledBorder("Phiên đăng nhập (mỗi tài khoản tối đa 1 phiên)"));
        sessions.add(new JScrollPane(sessionTable));
        JPanel stats = new JPanel(new BorderLayout());
        stats.setBorder(BorderFactory.createTitledBorder("Thống kê máy chủ, luồng và đặt chỗ"));
        stats.add(new JScrollPane(statsTable));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessions, stats);
        split.setResizeWeight(0.6);

        add(bar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        // Tự làm mới mỗi 3 giây khi tab đang hiển thị
        ctx.timer(3000, e -> {
            if (isShowing()) {
                reload();
            }
        }).start();
    }

    public void reload() {
        UiKit.callQuiet(ctx.conn(), Message.request(Command.ADMIN_GET_SESSIONS), r -> {
            SessionInfo sel = UiKit.selected(sessionTable, sessionModel);
            sessionModel.setRows(r.getList("sessions"));
            if (sel != null) {
                UiKit.reselect(sessionTable, sessionModel, s -> s.getId().equals(sel.getId()));
            }
        }, null);
        UiKit.callQuiet(ctx.conn(), Message.request(Command.ADMIN_GET_STATS), r -> {
            LinkedHashMap<String, String> stats = r.get("stats");
            List<Map.Entry<String, String>> rows = new ArrayList<>(stats.entrySet());
            statsModel.setRows(rows);
            updated.setText("  Cập nhật lúc " + java.time.LocalTime.now().withNano(0));
        }, null);
    }

    private void kickSelected() {
        SessionInfo s = UiKit.selected(sessionTable, sessionModel);
        if (s == null) {
            UiKit.info(this, "Hãy chọn một phiên trong bảng.");
            return;
        }
        if (!UiKit.confirm(this, "Ngắt phiên " + s.getId() + " của " + s.getFullName() + " (" + s.getIp() + ")?")) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_KICK_SESSION).put("sessionId", s.getId()), r -> {
            Toast.show(this, r.getText(), UiKit.INFO);
            reload();
        });
    }
}
