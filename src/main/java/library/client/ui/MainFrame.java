package library.client.ui;

import library.client.net.ServerConnection;
import library.client.ui.admin.BookingAdminPanel;
import library.client.ui.admin.StudentAdminPanel;
import library.client.ui.admin.SystemAdminPanel;
import library.client.ui.admin.ZoneSeatAdminPanel;
import library.common.Message;
import library.common.Protocol;
import library.common.ReputationInfo;
import library.common.UserInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;

/** Cửa sổ chính sau khi đăng nhập. Nhận sự kiện push từ server và điều phối tới các màn hình. */
public class MainFrame extends JFrame implements ServerConnection.Listener {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final JLabel banner = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final BookingPanel bookingPanel;
    private final MyBookingsPanel myBookingsPanel;
    private final AnnouncementsPanel announcementsPanel;
    private BookingAdminPanel bookingAdminPanel;
    private SystemAdminPanel systemAdminPanel;
    private StudentAdminPanel studentAdminPanel;
    private ReputationPanel reputationPanel;
    private final JLabel reputationBadge = new JLabel();
    private javax.swing.Timer reputationTimer;
    private boolean ended;
    private boolean reconnecting;

    public MainFrame(ServerConnection conn, UserInfo user) {
        super(Protocol.APP_NAME + " — " + user.getFullName());
        this.ctx = new ClientContext(conn, user, this);

        bookingPanel = new BookingPanel(ctx);
        myBookingsPanel = new MyBookingsPanel(ctx);
        announcementsPanel = new AnnouncementsPanel(ctx);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(UiKit.font(Font.BOLD, 13f));
        tabs.addTab("Đặt chỗ học", bookingPanel);
        tabs.addTab("Lịch đặt của tôi", myBookingsPanel);
        if (!user.isAdmin()) {
            reputationPanel = new ReputationPanel(ctx, this::showReputation);
            tabs.addTab("Điểm uy tín", reputationPanel);
        }
        tabs.addTab("Thông báo", announcementsPanel);
        if (user.isAdmin()) {
            bookingAdminPanel = new BookingAdminPanel(ctx);
            systemAdminPanel = new SystemAdminPanel(ctx);
            studentAdminPanel = new StudentAdminPanel(ctx);
            tabs.addTab("QT: Khu vực & chỗ ngồi", new ZoneSeatAdminPanel(ctx));
            tabs.addTab("QT: Quản lý đặt chỗ", bookingAdminPanel);
            tabs.addTab("QT: Sinh viên & uy tín", studentAdminPanel);
            tabs.addTab("QT: Phiên & hệ thống", systemAdminPanel);
        }
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == myBookingsPanel) {
                myBookingsPanel.reload();
            } else if (reputationPanel != null && tabs.getSelectedComponent() == reputationPanel) {
                reputationPanel.reload();
            } else if (tabs.getSelectedComponent() == studentAdminPanel) {
                studentAdminPanel.reload();
            } else if (tabs.getSelectedComponent() == bookingAdminPanel) {
                bookingAdminPanel.reload();
            } else if (tabs.getSelectedComponent() == systemAdminPanel) {
                systemAdminPanel.reload();
            }
        });

        banner.setOpaque(true);
        banner.setVisible(false);
        banner.setFont(UiKit.font(Font.BOLD, 13f));
        banner.setBorder(UiKit.padding(8, 14, 8, 14));

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildHeader(user), BorderLayout.NORTH);
        north.add(banner, BorderLayout.SOUTH);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiKit.BORDER), UiKit.padding(4, 10, 4, 10)));
        statusLabel.setFont(UiKit.font(Font.PLAIN, 12f));
        statusBar.add(statusLabel, BorderLayout.WEST);

        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });
        setSize(1200, 780);
        setMinimumSize(new Dimension(1000, 640));
        setLocationRelativeTo(null);

        conn.addListener(this);
        ctx.timer(2000, e -> updateStatusBar()).start();
        updateStatusBar();
        ctx.reloadZones();
        myBookingsPanel.reload();
        announcementsPanel.reload();
        reloadReputation();
    }

    /** Tải lại điểm uy tín (gom nhiều sự kiện liên tiếp thành 1 lần gọi server). */
    private void reloadReputation() {
        if (reputationPanel == null) {
            return;
        }
        if (reputationTimer == null) {
            reputationTimer = ctx.timer(600, e -> reputationPanel.reload());
            reputationTimer.setRepeats(false);
        }
        reputationTimer.restart();
    }

    /** Hiện điểm uy tín ở thanh tiêu đề (sinh viên). */
    private void showReputation(ReputationInfo info) {
        reputationBadge.setText("<html><div style='text-align:center'>Điểm uy tín<br><b><font size='+1' color='"
                + String.format("#%06X", UiKit.levelColor(info.getLevel()).brighter().getRGB() & 0xFFFFFF) + "'>"
                + info.getScore() + "</font></b> " + info.getLevel().getLabel() + "</div></html>");
        reputationBadge.setVisible(true);
    }

    private JPanel buildHeader(UserInfo user) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(UiKit.PRIMARY);
        header.setBorder(UiKit.padding(10, 16, 10, 12));
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(UiKit.label("<html>THƯ VIỆN <font color='#FFC857'>" + Protocol.GROUP_NAME.toUpperCase()
                + "</font></html>", Font.BOLD, 18f, Color.WHITE), BorderLayout.NORTH);
        left.add(UiKit.label("Hệ thống đặt chỗ học trực tuyến", Font.PLAIN, 12f, UiKit.PRIMARY_SOFT), BorderLayout.SOUTH);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        right.setOpaque(false);
        String who = "<html><div style='text-align:right'><b>" + UiKit.html(user.getFullName()) + "</b><br>"
                + user.getRole().getLabel()
                + (user.getStudentId() != null && !user.getStudentId().isEmpty() ? " • MSSV " + user.getStudentId() : "")
                + "</div></html>";
        reputationBadge.setForeground(Color.WHITE);
        reputationBadge.setFont(UiKit.font(Font.PLAIN, 12f));
        reputationBadge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x3B5B82)), UiKit.padding(0, 0, 0, 12)));
        reputationBadge.setVisible(false);
        right.add(reputationBadge);
        right.add(UiKit.label(who, Font.PLAIN, 12f, Color.WHITE));
        JButton logout = new JButton("Đăng xuất");
        logout.addActionListener(e -> logout());
        right.add(logout);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private void updateStatusBar() {
        ServerConnection conn = ctx.conn();
        String state;
        if (reconnecting) {
            state = "<font color='#B7791F'>● Đang kết nối lại...</font>";
        } else if (conn.isConnected()) {
            long ping = conn.getLastPingMillis();
            state = "<font color='#1E8E4F'>● Đã kết nối</font>" + (ping >= 0 ? " &nbsp;•&nbsp; Độ trễ " + ping + " ms" : "");
        } else {
            state = "<font color='#C93C3C'>● Mất kết nối</font>";
        }
        statusLabel.setText("<html>Máy chủ " + conn.getHost() + ":" + conn.getPort() + " &nbsp;•&nbsp; " + state
                + " &nbsp;•&nbsp; Tài khoản: " + UiKit.html(ctx.user().getUsername()) + "</html>");
    }

    private void showBanner(String text, Color bg) {
        banner.setText(text);
        banner.setBackground(bg);
        banner.setForeground(Color.WHITE);
        banner.setVisible(true);
    }

    // ------------------------------------------------------------------ Sự kiện từ server (luồng nền → EDT)

    @Override
    public void onEvent(Message event) {
        SwingUtilities.invokeLater(() -> handleEvent(event));
    }

    private void handleEvent(Message e) {
        if (ended) {
            return;
        }
        switch (e.getEvent()) {
            case SEATS_CHANGED: {
                LocalDate date = e.get("date");
                bookingPanel.onSeatsChanged(date, e.getInt("slot", 0));
                myBookingsPanel.requestRefresh();
                reloadReputation(); // check-in / hủy sát giờ có thể làm thay đổi điểm uy tín
                if (bookingAdminPanel != null) {
                    bookingAdminPanel.requestRefresh();
                }
                break;
            }
            case ZONES_CHANGED:
                ctx.reloadZones();
                break;
            case ANNOUNCEMENTS_CHANGED:
                announcementsPanel.reload();
                if (e.getText() != null) {
                    Toast.show(this, e.getText(), UiKit.PRIMARY);
                }
                break;
            case BOOKING_UPDATED:
                Toast.show(this, e.getText(), UiKit.WARNING);
                myBookingsPanel.reload();
                bookingPanel.requestRefresh();
                reloadReputation();
                break;
            case REPUTATION_CHANGED:
                Toast.show(this, e.getText(), UiKit.PRIMARY);
                reloadReputation();
                bookingPanel.requestRefresh();
                break;
            case FORCE_LOGOUT:
            case SESSION_EXPIRED:
                backToLogin(e.getText());
                break;
            case SERVER_SHUTDOWN:
                showBanner(e.getText(), UiKit.DANGER);
                break;
            default:
                break;
        }
    }

    @Override
    public void onConnectionLost() {
        SwingUtilities.invokeLater(() -> {
            reconnecting = true;
            showBanner("Mất kết nối tới máy chủ. Đang tự động kết nối lại - bạn không cần đăng nhập lại...",
                    UiKit.WARNING);
            updateStatusBar();
        });
    }

    @Override
    public void onReconnecting(int attempt) {
        SwingUtilities.invokeLater(() -> showBanner("Mất kết nối tới máy chủ. Đang thử kết nối lại (lần " + attempt
                + ")...", UiKit.WARNING));
    }

    @Override
    public void onReconnected() {
        SwingUtilities.invokeLater(() -> {
            reconnecting = false;
            banner.setVisible(false);
            Toast.show(this, "Đã kết nối lại máy chủ, phiên làm việc được giữ nguyên.", UiKit.SUCCESS);
            updateStatusBar();
            ctx.reloadZones();
            myBookingsPanel.reload();
            announcementsPanel.reload();
        });
    }

    @Override
    public void onSessionLost(String reason) {
        SwingUtilities.invokeLater(() -> backToLogin(reason));
    }

    // ------------------------------------------------------------------ Đăng xuất / thoát

    private void backToLogin(String reason) {
        if (ended) {
            return;
        }
        ended = true;
        ctx.dispose();
        ctx.conn().removeListener(this);
        ctx.conn().close();
        dispose();
        if (reason != null) {
            JOptionPane.showMessageDialog(null, reason, "Phiên làm việc kết thúc", JOptionPane.WARNING_MESSAGE);
        }
        new LoginFrame(reason).setVisible(true);
    }

    private void logout() {
        if (!UiKit.confirm(this, "Đăng xuất khỏi hệ thống?")) {
            return;
        }
        ended = true;
        ctx.dispose();
        ServerConnection conn = ctx.conn();
        conn.removeListener(this);
        new Thread(conn::logout, "logout").start();
        dispose();
        new LoginFrame().setVisible(true);
    }

    private void exitApplication() {
        if (!UiKit.confirm(this, "Thoát chương trình?")) {
            return;
        }
        ended = true;
        ctx.dispose();
        setVisible(false);
        Thread t = new Thread(() -> {
            ctx.conn().logout();
            System.exit(0);
        }, "exit");
        t.start();
    }
}
