package library.client.ui;

import library.common.BookingStatus;
import library.common.Seat;
import library.common.SeatState;
import library.common.SeatView;

import javax.swing.JComponent;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/** Ô chỗ ngồi trên sơ đồ - tự vẽ để có màu sắc nhất quán trên mọi hệ điều hành. */
public class SeatButton extends JComponent {
    private static final long serialVersionUID = 1L;

    static final Color AVAILABLE_FILL = new Color(0xE7F6EC);
    static final Color AVAILABLE_LINE = new Color(0x1E8E4F);
    static final Color BOOKED_FILL = new Color(0xFBE4E4);
    static final Color BOOKED_LINE = new Color(0xC93C3C);
    static final Color MINE_FILL = new Color(0xDCE8FF);
    static final Color MINE_LINE = new Color(0x2F6FED);
    static final Color OFF_FILL = new Color(0xECEDEF);
    static final Color OFF_LINE = new Color(0x9CA3AF);
    /** Bàn mở cho đặt lẻ từng chỗ / còn chỗ ngồi ghép. */
    static final Color SHARE_FILL = new Color(0xFFF1D0);
    static final Color SHARE_LINE = new Color(0xB7791F);
    static final Color OUTLET = new Color(0xE0A100);

    private final SeatView view;
    private final boolean slotOpen;
    private boolean hover;

    public SeatButton(SeatView view, boolean slotOpen, String zoneName, String bookedBy, Consumer<SeatView> onClick) {
        this.view = view;
        this.slotOpen = slotOpen;
        setPreferredSize(new Dimension(96, 60));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(buildTooltip(zoneName, bookedBy));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.accept(view);
            }
        });
    }

    private String buildTooltip(String zoneName, String bookedBy) {
        Seat s = view.getSeat();
        StringBuilder sb = new StringBuilder("<html><b>").append(s.getId()).append("</b> — ")
                .append(UiKit.html(zoneName)).append("<br>")
                .append(s.getType().getLabel()).append(" • ").append(s.getCapacity()).append(" người")
                .append(view.getSharedTaken() > 0 ? "<br>Ngồi ghép: " + view.getSharedTaken() + "/" + s.getCapacity()
                        + " chỗ đã có người" : "")
                .append(view.isShareOpen() ? "<br><b>Đang mở cho đặt lẻ từng chỗ</b> (khu chỗ đơn đã kín)" : "")
                .append("<br>Ổ cắm điện: ").append(s.isHasOutlet() ? "có" : "không")
                .append("<br>Trạng thái: <b>").append(stateText()).append("</b>");
        if (bookedBy != null) {
            sb.append("<br>Người đặt: ").append(UiKit.html(bookedBy));
        }
        if (view.getState() == SeatState.MAINTENANCE && s.getNote() != null) {
            sb.append("<br>Ghi chú: ").append(UiKit.html(s.getNote()));
        }
        return sb.append("</html>").toString();
    }

    private String stateText() {
        if (view.getState() == SeatState.AVAILABLE && view.isShareOpen()) {
            return "Còn trống - đặt cả bàn hoặc đặt lẻ 1 chỗ";
        }
        if (view.getState() == SeatState.MINE && view.getBookingStatus() == BookingStatus.CHECKED_IN) {
            return "Bạn đang sử dụng";
        }
        if (view.getState() == SeatState.BOOKED && view.getBookingStatus() == BookingStatus.CHECKED_IN) {
            return "Đang có người sử dụng";
        }
        return view.getState().getLabel();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (!slotOpen) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        }
        Color fill;
        Color line;
        switch (view.getState()) {
            case BOOKED:
                fill = BOOKED_FILL;
                line = BOOKED_LINE;
                break;
            case MINE:
                fill = MINE_FILL;
                line = MINE_LINE;
                break;
            case MAINTENANCE:
                fill = OFF_FILL;
                line = OFF_LINE;
                break;
            case PARTIAL:
                fill = SHARE_FILL;
                line = SHARE_LINE;
                break;
            default:
                if (view.isShareOpen()) {
                    fill = SHARE_FILL;
                    line = SHARE_LINE;
                    break;
                }
                fill = AVAILABLE_FILL;
                line = AVAILABLE_LINE;
        }
        int w = getWidth();
        int h = getHeight();
        if (hover) {
            fill = blend(fill, line, 0.18f);
        }
        g2.setColor(fill);
        g2.fillRoundRect(1, 1, w - 3, h - 3, 14, 14);
        g2.setColor(line);
        g2.setStroke(new BasicStroke(hover || view.getState() == SeatState.MINE ? 2.4f : 1.3f));
        g2.drawRoundRect(1, 1, w - 3, h - 3, 14, 14);

        Seat s = view.getSeat();
        Font idFont = UiKit.font(Font.BOLD, 15f);
        g2.setFont(idFont);
        g2.setColor(line.darker());
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(s.getId(), (w - fm.stringWidth(s.getId())) / 2, h / 2 - 2);

        String sub = subtitle(s);
        g2.setFont(UiKit.font(Font.PLAIN, 11f));
        fm = g2.getFontMetrics();
        g2.setColor(new Color(0x4B5563));
        g2.drawString(sub, (w - fm.stringWidth(sub)) / 2, h / 2 + fm.getAscent() + 2);

        if (s.isHasOutlet()) {
            g2.setColor(OUTLET);
            g2.fillOval(w - 14, 7, 7, 7);
        }
        if (view.getState() == SeatState.MAINTENANCE) { // gạch chéo
            g2.setColor(new Color(0x9CA3AF));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawLine(10, h - 10, w - 10, 10);
        }
        g2.dispose();
    }

    private String subtitle(Seat s) {
        if (view.getState() == SeatState.MAINTENANCE) {
            return "Bảo trì";
        }
        if (view.getState() == SeatState.MINE) {
            String place = view.getMyPlace() > 0 ? " · ghép #" + view.getMyPlace() : "";
            return (view.getBookingStatus() == BookingStatus.CHECKED_IN ? "Đang dùng" : "Của bạn") + place;
        }
        if (view.getSharedTaken() > 0) {
            return "Ghép " + view.getSharedTaken() + "/" + s.getCapacity() + " người";
        }
        if (view.getBookingStatus() == BookingStatus.CHECKED_IN) {
            return "Đang dùng";
        }
        if (view.getState() == SeatState.AVAILABLE && view.isShareOpen()) {
            return "Đặt lẻ · 0/" + s.getCapacity();
        }
        return s.getCapacity() > 1 ? s.getType().getShortLabel() + " · " + s.getCapacity() + " người"
                : s.getType().getShortLabel();
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    /** Ô màu nhỏ dùng cho chú thích. */
    static JComponent legendSwatch(Color fill, Color line, boolean dot) {
        return new JComponent() {
            private static final long serialVersionUID = 1L;

            {
                setPreferredSize(new Dimension(18, 14));
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (dot) {
                    g2.setColor(OUTLET);
                    g2.fillOval(5, 3, 8, 8);
                } else {
                    g2.setColor(fill);
                    g2.fillRoundRect(1, 1, 15, 11, 5, 5);
                    g2.setColor(line);
                    g2.drawRoundRect(1, 1, 15, 11, 5, 5);
                }
                g2.dispose();
            }
        };
    }
}
