package library.client.ui.admin;

import library.client.ui.ClientContext;
import library.client.ui.ListTableModel;
import library.client.ui.Toast;
import library.client.ui.UiKit;
import library.common.Booking;
import library.common.BookingStatus;
import library.common.Command;
import library.common.Fmt;
import library.common.Message;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Quản trị lượt đặt: lọc theo ngày / trạng thái / từ khóa, check-in hộ, xác nhận trả chỗ, hủy kèm lý do.
 * Hiển thị cả lượt đặt tại quầy cho sinh viên không có tài khoản (kèm SĐT, ghi chú).
 */
public class BookingAdminPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String ALL = "Tất cả";

    private final ClientContext ctx;
    private final JComboBox<Object> dateBox = new JComboBox<>();
    private final JComboBox<Object> statusBox = new JComboBox<>();
    private final JTextField search = new JTextField(16);
    private final JLabel count = new JLabel();
    private final ListTableModel<Booking> model = new ListTableModel<Booking>()
            .col("Mã", Booking::getId)
            .col("Sinh viên", Booking::getFullName)
            .col("MSSV", Booking::getStudentId)
            .col("Hình thức", b -> b.isWalkIn() ? "Tại quầy" : "Tự đặt")
            .col("Ngày", LocalDate.class, Booking::getDate)
            .col("Ca", Integer.class, Booking::getSlot)
            .col("Chỗ", Booking::getSeatLabel)
            .col("Trạng thái", BookingStatus.class, Booking::getStatus)
            .col("SĐT", Booking::getPhone)
            .col("Ghi chú SV", Booking::getGuestNote)
            .col("Người tạo", Booking::getUsername)
            .col("Đặt lúc", LocalDateTime.class, Booking::getCreatedAt)
            .col("Ghi chú hệ thống", Booking::getNote);
    private final JTable table = UiKit.table(model);
    private final Timer refreshTimer;

    public BookingAdminPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(10, 10, 10, 10));
        refreshTimer = ctx.timer(400, e -> reload());
        refreshTimer.setRepeats(false);

        dateBox.addItem(ALL);
        LocalDate today = LocalDate.now();
        for (int i = -7; i <= 6; i++) {
            dateBox.addItem(today.plusDays(i));
        }
        dateBox.setSelectedItem(today);
        dateBox.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof LocalDate) {
                    setText(Fmt.relativeDayLabel((LocalDate) value));
                }
                return this;
            }
        });
        statusBox.addItem(ALL);
        for (BookingStatus s : BookingStatus.values()) {
            statusBox.addItem(s);
        }
        dateBox.addActionListener(e -> reload());
        statusBox.addActionListener(e -> reload());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applySearch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applySearch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applySearch();
            }
        });

        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> reload());
        JButton cancel = new JButton("Hủy lượt đặt");
        cancel.addActionListener(e -> cancelSelected());
        JButton checkIn = new JButton("Check-in");
        checkIn.addActionListener(e -> act(Command.ADMIN_CHECK_IN, BookingStatus.ACTIVE));
        JButton checkOut = new JButton("Xác nhận trả chỗ");
        checkOut.addActionListener(e -> act(Command.ADMIN_CHECK_OUT, BookingStatus.CHECKED_IN));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(new JLabel("Ngày:"));
        bar.add(dateBox);
        bar.add(new JLabel("Trạng thái:"));
        bar.add(statusBox);
        bar.add(new JLabel("Tìm:"));
        bar.add(search);
        bar.add(refresh);
        bar.add(checkIn);
        bar.add(checkOut);
        bar.add(cancel);
        bar.add(count);

        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(130);
        table.getColumnModel().getColumn(5).setPreferredWidth(30);
        table.getColumnModel().getColumn(6).setPreferredWidth(45);
        table.getColumnModel().getColumn(9).setPreferredWidth(160);
        table.getColumnModel().getColumn(12).setPreferredWidth(200);

        JLabel hint = UiKit.label("Đặt chỗ hộ sinh viên không có tài khoản: vào tab \"Đặt chỗ học\", chọn ngày/ca rồi "
                + "bấm vào một chỗ màu xanh lá. Tìm theo tên, MSSV, SĐT, mã chỗ hoặc mã đặt chỗ.",
                java.awt.Font.PLAIN, 12f, UiKit.MUTED);

        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);
    }

    public void requestRefresh() {
        refreshTimer.restart();
    }

    public void reload() {
        Message req = Message.request(Command.ADMIN_GET_BOOKINGS);
        if (dateBox.getSelectedItem() instanceof LocalDate) {
            req.put("date", (LocalDate) dateBox.getSelectedItem());
        }
        if (statusBox.getSelectedItem() instanceof BookingStatus) {
            req.put("status", (BookingStatus) statusBox.getSelectedItem());
        }
        UiKit.callQuiet(ctx.conn(), req, r -> {
            Booking sel = UiKit.selected(table, model);
            List<Booking> list = r.getList("bookings");
            model.setRows(list);
            if (sel != null) {
                UiKit.reselect(table, model, b -> b.getId().equals(sel.getId()));
            }
            count.setText("  Tổng: " + list.size() + " lượt");
        }, null);
    }

    private void applySearch() {
        String text = search.getText().trim();
        @SuppressWarnings("unchecked")
        TableRowSorter<ListTableModel<Booking>> sorter = (TableRowSorter<ListTableModel<Booking>>) table.getRowSorter();
        sorter.setRowFilter(text.isEmpty() ? null
                : RowFilter.regexFilter("(?iu)" + Pattern.quote(text), 0, 1, 2, 6, 8, 10));
    }

    private void act(Command cmd, BookingStatus required) {
        Booking b = UiKit.selected(table, model);
        if (b == null) {
            UiKit.info(this, "Hãy chọn một lượt đặt trong bảng.");
            return;
        }
        if (b.getStatus() != required) {
            UiKit.info(this, cmd == Command.ADMIN_CHECK_IN
                    ? "Chỉ check-in được lượt đang ở trạng thái \"Đã đặt\"."
                    : "Chỉ xác nhận trả chỗ được với lượt \"Đang sử dụng\".");
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(cmd).put("bookingId", b.getId()), r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            reload();
        });
    }

    private void cancelSelected() {
        Booking b = UiKit.selected(table, model);
        if (b == null) {
            return;
        }
        if (!b.getStatus().isLive()) {
            UiKit.info(this, "Lượt đặt này đã kết thúc (" + b.getStatus().getLabel() + ").");
            return;
        }
        String reason = JOptionPane.showInputDialog(this,
                "Hủy lượt " + b.getId() + " của " + b.getFullName() + " (" + b.describe() + ")\n\n"
                        + "Lý do (sẽ gửi cho sinh viên):", "Hủy lượt đặt", JOptionPane.QUESTION_MESSAGE);
        if (reason == null) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_CANCEL_BOOKING)
                .put("bookingId", b.getId()).put("reason", reason), r -> {
            Toast.show(this, r.getText(), UiKit.INFO);
            reload();
        });
    }
}
