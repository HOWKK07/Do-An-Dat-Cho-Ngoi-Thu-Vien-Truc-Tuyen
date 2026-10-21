package library.client.ui;

import library.client.net.ServerConnection;
import library.common.BookingStatus;
import library.common.Fmt;
import library.common.Message;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Tiện ích giao diện dùng chung: màu sắc, hộp thoại, gọi server bất đồng bộ, bảng. */
public final class UiKit {
    public static final Color PRIMARY = new Color(0x1E3A5F);
    public static final Color PRIMARY_SOFT = new Color(0xDCE6F2);
    /** Màu nhấn cho tên nhóm trên nền xanh đậm. */
    public static final Color ACCENT = new Color(0xFFC857);
    public static final Color BG = new Color(0xF3F5F8);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(0xDDE2E8);
    public static final Color SUCCESS = new Color(0x1E8E4F);
    public static final Color DANGER = new Color(0xC93C3C);
    public static final Color WARNING = new Color(0xB7791F);
    public static final Color INFO = new Color(0x2F6FED);
    public static final Color MUTED = new Color(0x6B7280);

    private UiKit() {
    }

    public static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // dùng giao diện mặc định của Java
        }
        ToolTipManager.sharedInstance().setInitialDelay(250);
        ToolTipManager.sharedInstance().setDismissDelay(15000);
    }

    // ------------------------------------------------------------------ Font, nhãn, viền

    public static Font font(int style, float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        return base.deriveFont(style, size);
    }

    public static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        if (color != null) {
            l.setForeground(color);
        }
        if (size >= 16f) { // chừa chỗ phía trên cho dấu tiếng Việt chồng (Ố, Ặ...) không bị cắt
            l.setBorder(padding(4, 0, 0, 0));
        }
        return l;
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), padding(10, 12, 12, 12));
    }

    public static String html(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    // ------------------------------------------------------------------ Hộp thoại

    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Không thực hiện được", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Thông báo", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String msg) {
        return JOptionPane.showConfirmDialog(parent, msg, "Xác nhận", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Hộp thoại nhập liệu dạng form. Tham số: nhãn1, ô1, nhãn2, ô2... */
    public static boolean form(Component parent, String title, Object... labelsAndFields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i + 1 < labelsAndFields.length; i += 2) {
            c.gridy = i / 2;
            c.gridx = 0;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.NORTHWEST;
            p.add(new JLabel(String.valueOf(labelsAndFields[i])), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            p.add((JComponent) labelsAndFields[i + 1], c);
        }
        return JOptionPane.showConfirmDialog(parent, p, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // ------------------------------------------------------------------ Gọi server

    /**
     * Gửi yêu cầu bất đồng bộ (không chặn giao diện). Kết quả được xử lý trên luồng Swing:
     * thành công → onSuccess, thất bại → hiện hộp thoại lỗi và chạy onFailure (nếu có).
     */
    public static void call(Component parent, ServerConnection conn, Message request,
                            Consumer<Message> onSuccess, Runnable onFailure) {
        conn.send(request).whenComplete((resp, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                error(parent, "Không gửi được yêu cầu tới máy chủ.\n" + rootMessage(ex));
                if (onFailure != null) {
                    onFailure.run();
                }
            } else if (!resp.isSuccess()) {
                error(parent, resp.getText());
                if (onFailure != null) {
                    onFailure.run();
                }
            } else if (onSuccess != null) {
                onSuccess.accept(resp);
            }
        }));
    }

    public static void call(Component parent, ServerConnection conn, Message request, Consumer<Message> onSuccess) {
        call(parent, conn, request, onSuccess, null);
    }

    /** Gọi "im lặng" (dùng cho tự làm mới): lỗi chỉ được chuyển cho onError, không bật hộp thoại. */
    public static void callQuiet(ServerConnection conn, Message request, Consumer<Message> onSuccess,
                                 Consumer<String> onError) {
        conn.send(request).whenComplete((resp, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null || !resp.isSuccess()) {
                if (onError != null) {
                    onError.accept(ex != null ? rootMessage(ex) : resp.getText());
                }
            } else {
                onSuccess.accept(resp);
            }
        }));
    }

    public static String rootMessage(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof TimeoutException) {
            return "Máy chủ không phản hồi (quá thời gian chờ).";
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    // ------------------------------------------------------------------ Bảng

    public static JTable table(TableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(26);
        t.setAutoCreateRowSorter(true);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        t.setShowVerticalLines(false);
        t.setGridColor(new Color(0xE5E7EB));
        t.setIntercellSpacing(new Dimension(0, 1));
        t.getTableHeader().setFont(font(Font.BOLD, t.getTableHeader().getFont().getSize2D()));
        t.getTableHeader().setReorderingAllowed(false);
        t.setDefaultRenderer(LocalDate.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void setValue(Object v) {
                setText(v == null ? "" : Fmt.dayLabel((LocalDate) v));
            }
        });
        t.setDefaultRenderer(LocalDateTime.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void setValue(Object v) {
                setText(v == null ? "" : Fmt.dateTime((LocalDateTime) v));
            }
        });
        t.setDefaultRenderer(BookingStatus.class, new StatusRenderer());
        return t;
    }

    public static <T> T selected(JTable table, ListTableModel<T> model) {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.getRow(table.convertRowIndexToModel(row));
    }

    /** Chọn lại dòng thỏa điều kiện sau khi nạp lại dữ liệu. */
    public static <T> void reselect(JTable table, ListTableModel<T> model, Predicate<T> match) {
        for (int i = 0; i < model.getRowCount(); i++) {
            if (match.test(model.getRow(i))) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) {
                    table.setRowSelectionInterval(view, view);
                    table.scrollRectToVisible(table.getCellRect(view, 0, true));
                }
                return;
            }
        }
    }

    public static Color levelColor(library.common.Reputation.Level level) {
        switch (level) {
            case EXCELLENT:
                return SUCCESS;
            case GOOD:
                return INFO;
            case RESTRICTED:
                return WARNING;
            default:
                return DANGER;
        }
    }

    public static Color statusColor(BookingStatus s) {
        switch (s) {
            case ACTIVE:
                return INFO;
            case CHECKED_IN:
                return SUCCESS;
            case CANCELLED:
                return DANGER;
            case EXPIRED:
                return WARNING;
            default:
                return MUTED;
        }
    }

    /** Hiển thị trạng thái lượt đặt có màu. */
    static final class StatusRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof BookingStatus) {
                BookingStatus s = (BookingStatus) value;
                setText(s.getLabel());
                setFont(getFont().deriveFont(Font.BOLD));
                if (!isSelected) {
                    setForeground(statusColor(s));
                }
            }
            return this;
        }
    }
}
