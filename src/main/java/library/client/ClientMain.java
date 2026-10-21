package library.client;

import library.client.ui.LoginFrame;
import library.client.ui.UiKit;

import javax.swing.SwingUtilities;

/** Điểm khởi động ứng dụng client (giao diện Swing). */
public final class ClientMain {

    private ClientMain() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(() -> {
            UiKit.installLookAndFeel();
            new LoginFrame().setVisible(true);
        });
    }
}
