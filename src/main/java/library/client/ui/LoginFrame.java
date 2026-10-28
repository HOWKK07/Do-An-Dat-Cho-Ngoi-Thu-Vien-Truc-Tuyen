package library.client.ui;

import library.client.net.GoogleSignIn;
import library.client.net.ServerConnection;
import library.common.Command;
import library.common.Message;
import library.common.Protocol;
import library.common.UserInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

/** Màn hình đăng nhập: chọn máy chủ (nhập tay hoặc tìm trong LAN), đăng nhập, đăng ký. */
public class LoginFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private final JTextField hostField = new JTextField(12);
    private final JTextField portField = new JTextField(5);
    private final JTextField userField = new JTextField(20);
    private final JPasswordField passField = new JPasswordField(20);
    private final JCheckBox remember = new JCheckBox("Ghi nhớ máy chủ và tên đăng nhập", true);
    private final JButton loginButton = new JButton("Đăng nhập");
    private final JButton registerButton = new JButton("Đăng ký tài khoản");
    private final JButton findButton = new JButton("Tìm trong LAN");
    private final JButton googleButton = new JButton("Đăng nhập bằng Google");
    /** Khác null khi đang chờ người dùng đăng nhập Google trên trình duyệt (bấm nút lần nữa để hủy). */
    private volatile GoogleSignIn activeGoogle;
    private final JLabel status = new JLabel(" ");

    public LoginFrame() {
        this(null);
    }

    /** @param notice thông báo hiển thị sẵn (VD: lý do bị đăng xuất) */
    public LoginFrame(String notice) {
        super("Đăng nhập - " + Protocol.APP_NAME);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        getContentPane().add(buildBrandPanel(), BorderLayout.WEST);
        getContentPane().add(buildForm(), BorderLayout.CENTER);
        loadPreferences();

        loginButton.addActionListener(e -> doLogin());
        googleButton.addActionListener(e -> {
            GoogleSignIn g = activeGoogle;
            if (g != null) {
                g.cancel();
            } else {
                doGoogleLogin();
            }
        });
        passField.addActionListener(e -> doLogin());
        userField.addActionListener(e -> passField.requestFocusInWindow());
        registerButton.addActionListener(e -> new RegisterDialog(this, host(), port()).setVisible(true));
        findButton.addActionListener(e -> {
            DiscoveryDialog d = new DiscoveryDialog(this);
            d.setVisible(true);
            if (d.getSelected() != null) {
                hostField.setText(d.getSelected().getHost());
                portField.setText(String.valueOf(d.getSelected().getPort()));
                showStatus("Đã chọn máy chủ " + d.getSelected().getName(), UiKit.SUCCESS);
            }
        });
        getRootPane().setDefaultButton(loginButton);

        if (notice != null && !notice.isEmpty()) {
            showStatus(notice, UiKit.DANGER);
        }
        setSize(860, 540);
        setMinimumSize(new Dimension(780, 500));
        setLocationRelativeTo(null);
    }

    private JPanel buildBrandPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(UiKit.PRIMARY);
        p.setPreferredSize(new Dimension(320, 0));
        p.setBorder(UiKit.padding(48, 32, 32, 28));
        p.add(UiKit.label("THƯ VIỆN", Font.BOLD, 30f, Color.WHITE));
        p.add(UiKit.label(Protocol.BRAND.toUpperCase(), Font.BOLD, 34f, UiKit.ACCENT));
        p.add(Box.createVerticalStrut(6));
        p.add(UiKit.label("Hệ thống đặt chỗ học trực tuyến", Font.PLAIN, 15f, UiKit.PRIMARY_SOFT));
        p.add(Box.createVerticalStrut(36));
        String[] features = {
                "Sơ đồ chỗ trống theo thời gian thực",
                "Đặt, hủy, check-in theo ca học",
                "Nhận thông báo tức thì từ thư viện",
                "Tự kết nối lại khi mạng chập chờn"};
        for (String f : features) {
            p.add(UiKit.label("•  " + f, Font.PLAIN, 13f, Color.WHITE));
            p.add(Box.createVerticalStrut(10));
        }
        p.add(Box.createVerticalGlue());
        p.add(UiKit.label("Nhóm " + Protocol.GROUP_NAME + "  •  Phiên bản " + Protocol.VERSION, Font.PLAIN, 11f,
                UiKit.PRIMARY_SOFT));
        return p;
    }

    private JPanel buildForm() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(UiKit.padding(36, 40, 24, 40));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        p.add(UiKit.label("Đăng nhập", Font.BOLD, 22f, UiKit.PRIMARY), c);
        c.gridy++;
        p.add(UiKit.label("Dùng tài khoản thư viện để đặt chỗ học", Font.PLAIN, 13f, UiKit.MUTED), c);

        c.gridwidth = 1;
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Máy chủ"), c);
        JPanel serverRow = new JPanel(new BorderLayout(6, 0));
        JPanel portAndFind = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portAndFind.add(new JLabel(":"));
        portAndFind.add(portField);
        portAndFind.add(findButton);
        serverRow.add(hostField, BorderLayout.CENTER);
        serverRow.add(portAndFind, BorderLayout.EAST);
        c.gridx = 1;
        c.weightx = 1;
        p.add(serverRow, c);

        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Tên đăng nhập"), c);
        c.gridx = 1;
        p.add(userField, c);

        c.gridy++;
        c.gridx = 0;
        p.add(new JLabel("Mật khẩu"), c);
        c.gridx = 1;
        p.add(passField, c);

        c.gridy++;
        p.add(remember, c);

        c.gridy++;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        loginButton.setFont(UiKit.font(Font.BOLD, 13f));
        loginButton.setPreferredSize(new Dimension(150, 34));
        registerButton.setPreferredSize(new Dimension(160, 34));
        buttons.add(loginButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(registerButton);
        p.add(buttons, c);

        c.gridy++;
        googleButton.setPreferredSize(new Dimension(320, 34));
        googleButton.setToolTipText("Mở trình duyệt để đăng nhập bằng tài khoản Google (Gmail)");
        JPanel googleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        googleRow.add(googleButton);
        p.add(googleRow, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        status.setFont(UiKit.font(Font.PLAIN, 13f));
        p.add(status, c);

        c.gridy++;
        c.weighty = 1;
        c.anchor = GridBagConstraints.SOUTHWEST;
        JLabel hint = UiKit.label("<html>Tài khoản mẫu: <b>admin</b> / admin123 (quản trị) &nbsp;•&nbsp; "
                + "<b>thinh</b>, <b>truong</b>, <b>bao</b> / 123456</html>", Font.PLAIN, 12f, UiKit.MUTED);
        hint.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiKit.BORDER));
        p.add(hint, c);
        return p;
    }

    private void doLogin() {
        String host = host();
        int port = port();
        String username = userField.getText().trim();
        String password = new String(passField.getPassword());
        if (host.isEmpty() || port <= 0) {
            showStatus("Vui lòng nhập địa chỉ và cổng máy chủ hợp lệ.", UiKit.DANGER);
            return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            showStatus("Vui lòng nhập tên đăng nhập và mật khẩu.", UiKit.DANGER);
            return;
        }
        setBusy(true);
        showStatus("Đang kết nối tới " + host + ":" + port + "...", UiKit.MUTED);

        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                ServerConnection conn = new ServerConnection(host, port);
                conn.connect();
                Message r = conn.sendAndWait(Message.request(Command.LOGIN)
                        .put("username", username).put("password", password));
                if (!r.isSuccess()) {
                    conn.close();
                    throw new Exception(r.getText());
                }
                conn.setSessionToken(r.getString("token"));
                return new Object[]{conn, r.get("user")};
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    Object[] result = get();
                    savePreferences();
                    new MainFrame((ServerConnection) result[0], (UserInfo) result[1]).setVisible(true);
                    dispose();
                } catch (ExecutionException e) {
                    showStatus(UiKit.rootMessage(e), UiKit.DANGER);
                    passField.selectAll();
                    passField.requestFocusInWindow();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    void prefillUsername(String username) {
        userField.setText(username);
        passField.setText("");
        passField.requestFocusInWindow();
        showStatus("Đăng ký thành công. Hãy nhập mật khẩu để đăng nhập.", UiKit.SUCCESS);
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
        findButton.setEnabled(!busy);
        googleButton.setEnabled(!busy);
    }

    // ------------------------------------------------------------------ Đăng nhập bằng Google

    private void doGoogleLogin() {
        String host = host();
        int port = port();
        if (host.isEmpty() || port <= 0) {
            showStatus("Vui lòng nhập địa chỉ và cổng máy chủ hợp lệ.", UiKit.DANGER);
            return;
        }
        GoogleSignIn sign = new GoogleSignIn();
        ServerConnection[] holder = new ServerConnection[1];
        setBusy(true);
        showStatus("Đang kết nối tới " + host + ":" + port + "...", UiKit.MUTED);

        new SwingWorker<Message, String>() {
            @Override
            protected Message doInBackground() throws Exception {
                ServerConnection conn = new ServerConnection(host, port);
                holder[0] = conn;
                conn.connect();
                Message cfg = conn.sendAndWait(Message.request(Command.GOOGLE_CONFIG));
                if (!cfg.isSuccess() || !cfg.getBool("enabled")) {
                    throw new Exception("Máy chủ chưa bật đăng nhập bằng Google. Quản trị viên cần điền Client ID và "
                            + "Client secret vào file google.properties của server (xem README).");
                }
                activeGoogle = sign;
                SwingUtilities.invokeLater(() -> {
                    googleButton.setEnabled(true);
                    googleButton.setText("Hủy đăng nhập Google");
                });
                publish("Đã mở trình duyệt - hãy chọn tài khoản Google và bấm Tiếp tục. Đang chờ...");
                GoogleSignIn.Result g = sign.run(cfg.getString("authUrl"), cfg.getString("clientId"),
                        GoogleSignIn::openBrowser, 180);
                activeGoogle = null;
                publish("Đang xác minh tài khoản Google với máy chủ...");
                Message r = conn.sendAndWait(Message.request(Command.GOOGLE_LOGIN).put("code", g.code)
                        .put("codeVerifier", g.codeVerifier).put("redirectUri", g.redirectUri));
                if (!r.isSuccess()) {
                    throw new Exception(r.getText());
                }
                return r;
            }

            @Override
            protected void process(List<String> chunks) {
                showStatus(chunks.get(chunks.size() - 1), UiKit.MUTED);
            }

            @Override
            protected void done() {
                activeGoogle = null;
                googleButton.setText("Đăng nhập bằng Google");
                try {
                    Message r = get();
                    if (r.getBool("needLink")) {
                        completeGoogle(holder[0], new GoogleLinkDialog(r), null);
                    } else {
                        openMain(holder[0], r);
                    }
                } catch (ExecutionException e) {
                    setBusy(false);
                    closeQuietly(holder[0]);
                    showStatus(UiKit.rootMessage(e), UiKit.DANGER);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /** Tài khoản Google chưa liên kết: hỏi tạo mới / liên kết, gửi lên server; lỗi thì hỏi lại kèm thông báo. */
    private void completeGoogle(ServerConnection conn, GoogleLinkDialog dialog, String error) {
        Message req = dialog.ask(this, error);
        if (req == null) {
            setBusy(false);
            closeQuietly(conn);
            showStatus("Đã hủy đăng nhập bằng Google.", UiKit.MUTED);
            return;
        }
        showStatus("Đang hoàn tất đăng nhập...", UiKit.MUTED);
        conn.send(req).whenComplete((r, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                setBusy(false);
                closeQuietly(conn);
                showStatus("Mất kết nối tới máy chủ: " + UiKit.rootMessage(ex), UiKit.DANGER);
            } else if (!r.isSuccess()) {
                completeGoogle(conn, dialog, r.getText());
            } else {
                openMain(conn, r);
            }
        }));
    }

    private void openMain(ServerConnection conn, Message loginResponse) {
        setBusy(false);
        conn.setSessionToken(loginResponse.getString("token"));
        savePreferences();
        new MainFrame(conn, loginResponse.get("user")).setVisible(true);
        dispose();
    }

    private static void closeQuietly(ServerConnection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (RuntimeException ignored) {
                // đã đóng
            }
        }
    }

    private void showStatus(String text, Color color) {
        status.setForeground(color);
        status.setText("<html><div style='width:360px'>" + UiKit.html(text) + "</div></html>");
    }

    private String host() {
        return hostField.getText().trim();
    }

    private int port() {
        try {
            return Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void loadPreferences() {
        String host = "localhost";
        String port = String.valueOf(Protocol.DEFAULT_PORT);
        String user = "";
        try {
            Preferences p = Preferences.userNodeForPackage(LoginFrame.class);
            host = p.get("host", host);
            port = p.get("port", port);
            user = p.get("username", user);
        } catch (RuntimeException ignored) {
            // không truy cập được bộ nhớ cấu hình - dùng mặc định
        }
        hostField.setText(host);
        portField.setText(port);
        userField.setText(user);
    }

    private void savePreferences() {
        try {
            Preferences p = Preferences.userNodeForPackage(LoginFrame.class);
            if (remember.isSelected()) {
                p.put("host", host());
                p.put("port", String.valueOf(port()));
                p.put("username", userField.getText().trim());
            } else {
                p.remove("username");
            }
        } catch (RuntimeException ignored) {
            // bỏ qua
        }
    }
}
