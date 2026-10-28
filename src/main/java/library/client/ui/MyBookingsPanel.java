package library.client.ui;

import library.common.Booking;
import library.common.BookingStatus;
import library.common.Command;
import library.common.Fmt;
import library.common.Message;
import library.common.Protocol;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.Timer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Danh sách lượt đặt của người dùng: check-in, trả chỗ, hủy. */
public class MyBookingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final ListTableModel<Booking> model = new ListTableModel<Booking>()
            .col("Mã đặt chỗ", Booking::getId)
            .col("Ngày", LocalDate.class, Booking::getDate)
            .col("Ca học", b -> b.getTimeSlot() != null ? b.getTimeSlot().getLabel() : "")
            .col("Chỗ", Booking::getSeatLabel)
            .col("Khu vực", Booking::getZoneName)
            .col("Trạng thái", BookingStatus.class, Booking::getStatus)
            .col("Đặt lúc", LocalDateTime.class, Booking::getCreatedAt)
            .col("Ghi chú", Booking::getNote);
    private final JTable table = UiKit.table(model);
    private final JButton checkIn = new JButton("Check-in");
    private final JButton checkOut = new JButton("Trả chỗ");
    private final JButton cancel = new JButton("Hủy lượt đặt");
    private final JCheckBox onlyLive = new JCheckBox("Chỉ hiện lượt còn hiệu lực", true);
    private final JLabel countLabel = new JLabel();
    private final Timer refreshTimer;

    public MyBookingsPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(12, 12, 12, 12));
        refreshTimer = ctx.timer(300, e -> reload());
        refreshTimer.setRepeats(false);

        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(50);
        table.getColumnModel().getColumn(4).setPreferredWidth(190);
        table.getColumnModel().getColumn(7).setPreferredWidth(260);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        applyFilter();

        checkIn.addActionListener(e -> act(Command.CHECK_IN, null));
        checkOut.addActionListener(e -> act(Command.CHECK_OUT, "Trả chỗ và kết thúc lượt sử dụng?"));
        cancel.addActionListener(e -> act(Command.CANCEL_BOOKING, "Hủy lượt đặt đã chọn?"));
        onlyLive.addActionListener(e -> applyFilter());
        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> reload());

        JPanel toolbar = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        checkIn.setFont(UiKit.font(Font.BOLD, 13f));
        left.add(checkIn);
        left.add(checkOut);
        left.add(cancel);
        left.add(refresh);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(countLabel);
        right.add(onlyLive);
        toolbar.add(left, BorderLayout.WEST);
        toolbar.add(right, BorderLayout.EAST);

        JLabel rules = UiKit.label("<html><b>Quy định:</b> tối đa " + Protocol.MAX_BOOKINGS_PER_DAY
                + " ca/ngày, đặt trước tối đa " + library.common.Reputation.Level.GOOD.getDaysAhead() + " ngày ("
                + Protocol.BOOKING_DAYS_AHEAD + " ngày khi đạt " + library.common.Reputation.MAX
                + " điểm uy tín). Check-in trong khoảng "
                + Protocol.CHECKIN_EARLY_MINUTES + " phút trước đến " + Protocol.CHECKIN_LATE_MINUTES
                + " phút sau giờ bắt đầu ca; quá hạn hệ thống tự hủy để nhường chỗ và <b>trừ "
                + library.common.Reputation.NO_SHOW_PENALTY + " điểm uy tín</b>. Hủy dưới "
                + library.common.Reputation.LATE_CANCEL_MINUTES + " phút trước ca: trừ "
                + library.common.Reputation.LATE_CANCEL_PENALTY + " điểm. Check-in đúng hẹn: cộng "
                + library.common.Reputation.CHECK_IN_REWARD + " điểm.</html>", Font.PLAIN, 12f, UiKit.MUTED);

        add(toolbar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(rules, BorderLayout.SOUTH);
        updateButtons();
    }

    public void requestRefresh() {
        refreshTimer.restart();
    }

    public void reload() {
        UiKit.callQuiet(ctx.conn(), Message.request(Command.GET_MY_BOOKINGS), r -> {
            Booking sel = UiKit.selected(table, model);
            List<Booking> list = r.getList("bookings");
            model.setRows(list);
            if (sel != null) {
                UiKit.reselect(table, model, b -> b.getId().equals(sel.getId()));
            }
            long live = list.stream().filter(b -> b.getStatus().isLive()).count();
            countLabel.setText(live + " lượt còn hiệu lực / " + list.size() + " lượt  ");
            updateButtons();
        }, null);
    }

    private void applyFilter() {
        @SuppressWarnings("unchecked")
        TableRowSorter<ListTableModel<Booking>> sorter = (TableRowSorter<ListTableModel<Booking>>) table.getRowSorter();
        sorter.setRowFilter(onlyLive.isSelected() ? new RowFilter<ListTableModel<Booking>, Integer>() {
            @Override
            public boolean include(Entry<? extends ListTableModel<Booking>, ? extends Integer> entry) {
                return entry.getModel().getRow(entry.getIdentifier()).getStatus().isLive();
            }
        } : null);
    }

    private void updateButtons() {
        Booking b = UiKit.selected(table, model);
        // Chỉ check-in được khi đã đến ca: từ 15 phút trước đến 15 phút sau giờ bắt đầu
        LocalDateTime now = LocalDateTime.now();
        boolean canCheckIn = false;
        String tip = null;
        if (b != null && b.getStatus() == BookingStatus.ACTIVE && b.getTimeSlot() != null) {
            LocalDateTime opens = b.getTimeSlot().checkInOpensAt(b.getDate());
            LocalDateTime closes = b.getTimeSlot().startAt(b.getDate()).plusMinutes(Protocol.CHECKIN_LATE_MINUTES);
            canCheckIn = !now.isBefore(opens) && !now.isAfter(closes);
            if (now.isBefore(opens)) {
                tip = "Chưa đến ca - check-in mở từ " + Fmt.time(opens) + " ngày " + Fmt.date(b.getDate());
            }
        }
        checkIn.setEnabled(canCheckIn);
        checkIn.setToolTipText(tip);
        checkOut.setEnabled(b != null && b.getStatus() == BookingStatus.CHECKED_IN);
        cancel.setEnabled(b != null && b.getStatus() == BookingStatus.ACTIVE);
    }

    private void act(Command command, String confirmText) {
        Booking b = UiKit.selected(table, model);
        if (b == null) {
            return;
        }
        if (confirmText != null && !UiKit.confirm(this, confirmText + "\n\n" + b.getId() + ": " + b.describe())) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(command).put("bookingId", b.getId()), r -> {
            Toast.show(this, r.getText(), command == Command.CANCEL_BOOKING ? UiKit.INFO : UiKit.SUCCESS);
            reload();
        });
    }
}
