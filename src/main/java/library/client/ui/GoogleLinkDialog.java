package library.client.ui;

import library.common.Command;
import library.common.Message;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Lần đầu đăng nhập bằng một tài khoản Google chưa liên kết: chọn TẠO tài khoản sinh viên mới (điền MSSV)
 * hoặc LIÊN KẾT với tài khoản thư viện đã có (nhập mật khẩu tài khoản đó). Giữ nguyên dữ liệu đã nhập giữa các lần
 * hỏi lại (khi server báo lỗi, VD trùng MSSV).
 */
final class GoogleLinkDialog {
    private final String ticket;
    private final String email;
    private final JRadioButton createNew = new JRadioButton("Tạo tài khoản sinh viên mới", true);
    private final JRadioButton linkExisting = new JRadioButton("Liên kết với tài khoản thư viện đã có");
    private final JTextField fullName = new JTextField(24);
    private final JTextField studentId = new JTextField(14);
    private final JTextField newUsername = new JTextField(16);
    private final JTextField oldUsername = new JTextField(16);
    private final JPasswordField oldPassword = new JPasswordField(16);
    private final JPanel panel = new JPanel(new GridBagLayout());

    GoogleLinkDialog(Message needLink) {
        this.ticket = needLink.getString("ticket");
        this.email = needLink.getString("email");
        fullName.setText(needLink.getString("name"));
        newUsername.setText(needLink.getString("suggestedUsername"));
        ButtonGroup g = new ButtonGroup();
        g.add(createNew);
        g.add(linkExisting);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 2, 3, 2);
        panel.add(UiKit.label("<html><b>Tài khoản Google:</b> " + UiKit.html(email) + "<br>"
                + "Tài khoản này chưa gắn với tài khoản thư viện nào. Chọn một cách:</html>", Font.PLAIN, 13f,
                UiKit.PRIMARY), c);
        c.insets = new Insets(10, 2, 2, 2);
        panel.add(createNew, c);
        JPanel a = box();
        row(a, 0, "Họ và tên *", fullName);
        row(a, 1, "Mã số sinh viên *", studentId);
        row(a, 2, "Tên đăng nhập *", newUsername);
        c.insets = new Insets(0, 24, 2, 2);
        panel.add(a, c);
        c.insets = new Insets(10, 2, 2, 2);
        panel.add(linkExisting, c);
        JPanel b = box();
        row(b, 0, "Tên đăng nhập", oldUsername);
        row(b, 1, "Mật khẩu", oldPassword);
        c.insets = new Insets(0, 24, 2, 2);
        panel.add(b, c);
        c.insets = new Insets(8, 2, 2, 2);
        panel.add(UiKit.label("<html><div style='width:380px'>Sau bước này, lần sau chỉ cần bấm "
                + "\"Đăng nhập bằng Google\". Tài khoản tạo mới bằng Google không có mật khẩu riêng.</div></html>",
                Font.PLAIN, 11f, UiKit.MUTED), c);

        Runnable toggle = () -> {
            boolean n = createNew.isSelected();
            for (Component x : new Component[]{fullName, studentId, newUsername}) {
                x.setEnabled(n);
            }
            oldUsername.setEnabled(!n);
            oldPassword.setEnabled(!n);
        };
        createNew.addActionListener(e -> toggle.run());
        linkExisting.addActionListener(e -> toggle.run());
        toggle.run();
    }

    /** Hiện hộp thoại; trả về yêu cầu GOOGLE_COMPLETE, hoặc null nếu người dùng hủy. */
    Message ask(Component parent, String error) {
        Object content = panel;
        if (error != null) {
            JPanel withError = new JPanel(new java.awt.BorderLayout(0, 8));
            withError.add(UiKit.label("<html><div style='width:380px'>" + UiKit.html(error) + "</div></html>",
                    Font.BOLD, 12f, UiKit.DANGER), java.awt.BorderLayout.NORTH);
            withError.add(panel, java.awt.BorderLayout.CENTER);
            content = withError;
        }
        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, content, "Hoàn tất đăng nhập bằng Google",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            String problem = validate();
            if (problem == null) {
                break;
            }
            UiKit.error(parent, problem);
        }
        Message req = Message.request(Command.GOOGLE_COMPLETE).put("ticket", ticket);
        if (createNew.isSelected()) {
            return req.put("mode", "register").put("fullName", fullName.getText().trim())
                    .put("studentId", studentId.getText().trim()).put("username", newUsername.getText().trim());
        }
        return req.put("mode", "link").put("username", oldUsername.getText().trim())
                .put("password", new String(oldPassword.getPassword()));
    }

    private String validate() {
        if (createNew.isSelected()) {
            if (fullName.getText().trim().length() < 2) {
                return "Vui lòng nhập họ và tên.";
            }
            if (!studentId.getText().trim().matches("\\d{6,12}")) {
                return "Mã số sinh viên phải gồm 6-12 chữ số.";
            }
            if (!newUsername.getText().trim().toLowerCase().matches("[a-z0-9_.]{3,20}")) {
                return "Tên đăng nhập gồm 3-20 ký tự: chữ thường không dấu, số, dấu _ hoặc dấu chấm.";
            }
            return null;
        }
        if (oldUsername.getText().trim().isEmpty() || oldPassword.getPassword().length == 0) {
            return "Vui lòng nhập tên đăng nhập và mật khẩu của tài khoản cần liên kết.";
        }
        return null;
    }

    private static JPanel box() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return p;
    }

    private static void row(JPanel p, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(2, 2, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        p.add(field, c);
    }
}
