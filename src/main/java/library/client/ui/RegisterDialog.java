package library.client.ui;

import library.client.net.ServerConnection;
import library.common.Command;
import library.common.Message;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.ExecutionException;

/** Hộp thoại đăng ký tài khoản sinh viên. */
public class RegisterDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JTextField username = new JTextField(20);
    private final JTextField fullName = new JTextField(20);
    private final JTextField studentId = new JTextField(20);
    private final JPasswordField password = new JPasswordField(20);
    private final JPasswordField confirm = new JPasswordField(20);
    private final JButton submit = new JButton("Đăng ký");
    private final JLabel status = new JLabel(" ");
    private final LoginFrame owner;
    private final String host;
    private final int port;

    public RegisterDialog(LoginFrame owner, String host, int port) {
        super(owner, "Đăng ký tài khoản sinh viên", true);
        this.owner = owner;
        this.host = host;
        this.port = port;

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(UiKit.padding(16, 20, 8, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 2;
        form.add(UiKit.label("Tạo tài khoản mới", Font.BOLD, 18f, UiKit.PRIMARY), c);
        c.gridy = 1;
        form.add(UiKit.label("Máy chủ: " + host + ":" + port, Font.PLAIN, 12f, UiKit.MUTED), c);
        c.gridwidth = 1;
        addRow(form, c, 2, "Họ và tên", fullName);
        addRow(form, c, 3, "Mã số sinh viên", studentId);
        addRow(form, c, 4, "Tên đăng nhập", username);
        addRow(form, c, 5, "Mật khẩu (≥ 6 ký tự)", password);
        addRow(form, c, 6, "Nhập lại mật khẩu", confirm);
        c.gridy = 7;
        c.gridx = 0;
        c.gridwidth = 2;
        form.add(status, c);

        JButton cancel = new JButton("Đóng");
        cancel.addActionListener(e -> dispose());
        submit.addActionListener(e -> doRegister());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(submit);
        buttons.add(cancel);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(submit);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
    }

    private void doRegister() {
        String pw = new String(password.getPassword());
        if (!pw.equals(new String(confirm.getPassword()))) {
            show("Mật khẩu nhập lại không khớp.", UiKit.DANGER);
            return;
        }
        if (fullName.getText().trim().isEmpty() || studentId.getText().trim().isEmpty()
                || username.getText().trim().isEmpty() || pw.isEmpty()) {
            show("Vui lòng điền đầy đủ thông tin.", UiKit.DANGER);
            return;
        }
        if (host.isEmpty() || port <= 0) {
            show("Hãy nhập địa chỉ máy chủ ở màn hình đăng nhập trước.", UiKit.DANGER);
            return;
        }
        submit.setEnabled(false);
        show("Đang gửi...", UiKit.MUTED);
        Message req = Message.request(Command.REGISTER)
                .put("username", username.getText().trim())
                .put("password", pw)
                .put("fullName", fullName.getText().trim())
                .put("studentId", studentId.getText().trim());

        new SwingWorker<Message, Void>() {
            @Override
            protected Message doInBackground() throws Exception {
                ServerConnection conn = new ServerConnection(host, port);
                try {
                    conn.connect();
                    return conn.sendAndWait(req);
                } finally {
                    conn.close();
                }
            }

            @Override
            protected void done() {
                submit.setEnabled(true);
                try {
                    Message r = get();
                    if (r.isSuccess()) {
                        UiKit.info(RegisterDialog.this, r.getText());
                        owner.prefillUsername(username.getText().trim().toLowerCase());
                        dispose();
                    } else {
                        show(r.getText(), UiKit.DANGER);
                    }
                } catch (ExecutionException e) {
                    show(UiKit.rootMessage(e), UiKit.DANGER);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void show(String text, Color color) {
        status.setForeground(color);
        status.setText("<html><div style='width:320px'>" + UiKit.html(text) + "</div></html>");
        pack();
    }
}
