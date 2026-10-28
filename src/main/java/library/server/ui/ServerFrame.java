package library.server.ui;

import library.common.Fmt;
import library.common.SessionInfo;
import library.server.LibraryServer;
import library.server.Log;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cửa sổ giám sát server: phiên đăng nhập, thống kê luồng, nhật ký hoạt động. */
public class ServerFrame extends JFrame {
    private static final int MAX_LOG_LINES = 3000;
    /** Bảng phiên (trong bộ nhớ) cập nhật mỗi giây; thống kê cần truy vấn CSDL chỉ cập nhật mỗi 5 giây. */
    private static final int STATS_EVERY_TICKS = 5;

    private final LibraryServer server;
    private final JTextArea logArea = new JTextArea();
    private final JLabel infoLabel = new JLabel();
    private final DefaultTableModel sessionModel = new ReadOnlyModel(new String[]{
            "Mã phiên", "Tài khoản", "Họ tên", "Vai trò", "Địa chỉ IP", "Trạng thái", "Đăng nhập lúc", "Hoạt động cuối"});
    private final DefaultTableModel statsModel = new ReadOnlyModel(new String[]{"Chỉ số", "Giá trị"});
    private final JTable sessionTable = new JTable(sessionModel);
    private int tick;
    private boolean statsLoading;

    public ServerFrame(LibraryServer server) {
        super("Máy chủ " + server.getConfig().getServerName());
        this.server = server;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // dùng giao diện mặc định
        }

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(0x1E3A5F));
        header.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        JLabel title = new JLabel("MÁY CHỦ " + server.getConfig().getServerName().toUpperCase());
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0)); // chừa chỗ cho dấu tiếng Việt chồng (Ố, Ộ...)
        infoLabel.setForeground(new Color(0xDCE6F2));
        header.add(title, BorderLayout.NORTH);
        header.add(infoLabel, BorderLayout.SOUTH);

        sessionTable.setRowHeight(24);
        JTable statsTable = new JTable(statsModel);
        statsTable.setRowHeight(22);
        statsTable.getColumnModel().getColumn(0).setPreferredWidth(240);

        JPanel sessionsPanel = titled("Phiên đăng nhập", new JScrollPane(sessionTable));
        JPanel statsPanel = titled("Thống kê hệ thống & luồng", new JScrollPane(statsTable));
        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionsPanel, statsPanel);
        top.setResizeWeight(0.62);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel logPanel = titled("Nhật ký hoạt động (tên luồng hiển thị trong [ ])", new JScrollPane(logArea));
        logPanel.setPreferredSize(new java.awt.Dimension(100, 280));

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logPanel);
        center.setResizeWeight(0.5);

        JButton kick = new JButton("Ngắt phiên đã chọn");
        kick.addActionListener(e -> kickSelected());
        JButton save = new JButton("Kiểm tra kết nối MySQL");
        save.addActionListener(e -> new Thread(() -> {
            String result;
            try {
                result = server.getDatabase().ping();
            } catch (RuntimeException ex) {
                result = "Lỗi: " + ex.getMessage();
            }
            String text = result;
            Log.info("%s", text);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, text));
        }, "db-ping").start());
        JButton stop = new JButton("Dừng server và thoát");
        stop.addActionListener(e -> confirmExit());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(kick);
        buttons.add(save);
        buttons.add(stop);

        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        Log.addListener(line -> SwingUtilities.invokeLater(() -> appendLog(line)));
        new Timer(1000, e -> refresh()).start();

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmExit();
            }
        });
        setSize(1150, 720);
        setLocationRelativeTo(null);
    }

    private static JPanel titled(String title, java.awt.Component c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6),
                BorderFactory.createTitledBorder(title)));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private void refresh() {
        if (!isShowing() || (getExtendedState() & ICONIFIED) != 0) {
            tick = 0; // cửa sổ đang ẩn / thu nhỏ → không tốn tài nguyên cập nhật; mở lại thì cập nhật ngay
            return;
        }
        if (!server.isRunning()) {
            infoLabel.setText("Server chưa chạy hoặc đã dừng");
            return;
        }
        if (tick++ % STATS_EVERY_TICKS == 0 && !statsLoading) {
            loadStatsInBackground();
        }

        String selected = selectedSessionId();
        List<SessionInfo> sessions = server.getSessions().snapshot();
        sessionModel.setRowCount(0);
        for (SessionInfo s : sessions) {
            sessionModel.addRow(new Object[]{s.getId(), s.getUsername(), s.getFullName(), s.getRole().getLabel(),
                    s.getIp(), s.getStatusLabel(), Fmt.dateTime(s.getLoginAt()), Fmt.dateTime(s.getLastActive())});
        }
        if (selected != null) {
            for (int i = 0; i < sessionModel.getRowCount(); i++) {
                if (selected.equals(sessionModel.getValueAt(i, 0))) {
                    sessionTable.setRowSelectionInterval(i, i);
                }
            }
        }

    }

    /** Truy vấn CSDL và dò card mạng trên luồng nền để không làm đơ cửa sổ. */
    private void loadStatsInBackground() {
        statsLoading = true;
        new SwingWorker<LinkedHashMap<String, String>, Void>() {
            private List<String> lan;

            @Override
            protected LinkedHashMap<String, String> doInBackground() {
                lan = LibraryServer.getLanAddresses();
                return server.getStats();
            }

            @Override
            protected void done() {
                statsLoading = false;
                try {
                    LinkedHashMap<String, String> stats = get();
                    infoLabel.setText("Đang chạy  •  Cổng TCP " + server.getConfig().getPort()
                            + (server.getConfig().isDiscoveryEnabled()
                            ? "  •  UDP tìm kiếm LAN " + server.getConfig().getDiscoveryPort() : "")
                            + "  •  Địa chỉ LAN: " + String.join(", ", lan));
                    statsModel.setRowCount(0);
                    for (Map.Entry<String, String> e : stats.entrySet()) {
                        statsModel.addRow(new Object[]{e.getKey(), e.getValue()});
                    }
                } catch (Exception e) {
                    statsModel.setRowCount(0);
                    statsModel.addRow(new Object[]{"Lỗi lấy thống kê", String.valueOf(e.getMessage())});
                }
            }
        }.execute();
    }

    private String selectedSessionId() {
        int row = sessionTable.getSelectedRow();
        return row < 0 ? null : (String) sessionModel.getValueAt(row, 0);
    }

    private void kickSelected() {
        String id = selectedSessionId();
        if (id == null) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một phiên trong bảng.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Ngắt phiên " + id + "?", "Xác nhận",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            server.getSessions().kick(id, "Quản trị viên máy chủ đã kết thúc phiên đăng nhập của bạn.");
            refresh();
        }
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        int excess = logArea.getLineCount() - MAX_LOG_LINES;
        if (excess > 0) {
            try {
                logArea.replaceRange("", 0, logArea.getLineEndOffset(excess - 1));
            } catch (BadLocationException ignored) {
                // bỏ qua
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void confirmExit() {
        int n = server.getConnectedClients();
        String msg = n > 0 ? "Đang có " + n + " client kết nối. Dừng server và thoát?" : "Dừng server và thoát?";
        if (JOptionPane.showConfirmDialog(this, msg, "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            new Thread(() -> {
                server.stop();
                System.exit(0);
            }, "shutdown").start();
        }
    }

    private static final class ReadOnlyModel extends DefaultTableModel {
        private static final long serialVersionUID = 1L;

        ReadOnlyModel(String[] columns) {
            super(columns, 0);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
