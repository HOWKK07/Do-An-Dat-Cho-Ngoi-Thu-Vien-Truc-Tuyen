package library.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Thông báo nhỏ hiện ở góc dưới bên phải cửa sổ rồi tự ẩn (không chặn thao tác). */
public final class Toast {
    private static final List<JWindow> VISIBLE = new ArrayList<>();
    private static final int MARGIN = 16;

    private Toast() {
    }

    public static void show(Component owner, String text, Color color) {
        Window parent = owner instanceof Window ? (Window) owner : SwingUtilities.getWindowAncestor(owner);
        if (parent == null || !parent.isShowing()) {
            return;
        }
        JWindow w = new JWindow(parent);
        JLabel label = new JLabel("<html><div style='width:280px'>" + UiKit.html(text) + "</div></html>");
        label.setOpaque(true);
        label.setBackground(color);
        label.setForeground(Color.WHITE);
        label.setFont(UiKit.font(Font.PLAIN, 13f));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color.darker()), UiKit.padding(10, 14, 10, 14)));
        w.add(label);
        w.pack();
        VISIBLE.add(w);
        layout(parent);
        w.setVisible(true);

        Timer t = new Timer(4500, e -> dispose(w, parent));
        t.setRepeats(false);
        t.start();
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dispose(w, parent);
            }
        });
    }

    private static void dispose(JWindow w, Window parent) {
        if (VISIBLE.remove(w)) {
            w.dispose();
            layout(parent);
        }
    }

    private static void layout(Window parent) {
        Point p = parent.getLocationOnScreen();
        int y = p.y + parent.getHeight() - MARGIN - 28;
        for (int i = VISIBLE.size() - 1; i >= 0; i--) {
            JWindow w = VISIBLE.get(i);
            y -= w.getHeight();
            w.setLocation(p.x + parent.getWidth() - w.getWidth() - MARGIN, y);
            y -= 8;
        }
    }
}
