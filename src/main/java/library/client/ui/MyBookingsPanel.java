package library.client.ui;

import library.common.Booking;
import library.common.BookingStatus;
import library.common.Command;
import library.common.Companion;
import library.common.Fmt;
import library.common.Message;
import library.common.Protocol;
import library.common.Reputation;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
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

/**
 * Danh sách lượt đặt của người dùng: check-in, trả chỗ, hủy, sửa người đi cùng.
 * Sinh viên còn có bảng "Bàn bạn được mời đi cùng" để bấm Đồng ý / Từ chối.
 */
public class MyBookingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final ListTableModel<Booking> model = new ListTableModel<Booking>()
            .col("Mã đặt chỗ", Booking::getId)
            .col("Ngày", LocalDate.class, Booking::getDate)
            .col("Ca học", b -> b.getTimeSlot() != null ? b.getTimeSlot().getLabel() : "")
            .col("Chỗ", Booking::getSeatLabel)
            .col("Khu vực", Booking::getZoneName)
            .col("Người đi cùng", Booking::getCompanionsLabel)
            .col("Trạng thái", BookingStatus.class, Booking::getStatus)
            .col("Đặt lúc", LocalDateTime.class, Booking::getCreatedAt)
            .col("Ghi chú", Booking::getNote);
    private final JTable table = UiKit.table(model);
    private final JButton checkIn = new JButton("Check-in");
    private final JButton checkOut = new JButton("Trả chỗ");
    private final JButton cancel = new JButton("Hủy lượt đặt");
    private final JButton editCompanions = new JButton("Sửa người đi cùng");
    private final JCheckBox onlyLive = new JCheckBox("Chỉ hiện lượt còn hiệu lực", true);
    private final JLabel countLabel = new JLabel();

    // ----- Bàn được mời đi cùng (chỉ sinh viên)
    private final ListTableModel<Booking> invites = new ListTableModel<Booking>()
            .col("Mã đặt chỗ", Booking::getId)
            .col("Người đặt", b -> b.getFullName() + (b.getStudentId() != null ? " (" + b.getStudentId() + ")" : ""))
            .col("Ngày", LocalDate.class, Booking::getDate)
            .col("Ca học", b -> b.getTimeSlot() != null ? b.getTimeSlot().getLabel() : "")
            .col("Chỗ", Booking::getSeatLabel)
            .col("Xác nhận của bạn", b -> b.getMyCompanionStatus() != null ? b.getMyCompanionStatus().getLabel() : "")
            .col("Cả nhóm", MyBookingsPanel::groupLabel)
            .col("Trạng thái lượt đặt", BookingStatus.class, Booking::getStatus);
    private final JTable inviteTable = UiKit.table(invites);
    private final JButton accept = new JButton("Đồng ý đi cùng");
    private final JButton decline = new JButton("Từ chối");
    private final JLabel inviteCount = new JLabel();

    private final Timer refreshTimer;
    private boolean firstLoad = true;

    public MyBookingsPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(12, 12, 12, 12));
        refreshTimer = ctx.timer(300, e -> reload());
        refreshTimer.setRepeats(false);

        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(170);
        table.getColumnModel().getColumn(5).setPreferredWidth(320);   // Người đi cùng
        table.getColumnModel().getColumn(7).setPreferredWidth(120);
        table.getColumnModel().getColumn(8).setPreferredWidth(200);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        inviteTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        inviteTable.getColumnModel().getColumn(2).setPreferredWidth(140);
        inviteTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        inviteTable.getColumnModel().getColumn(5).setPreferredWidth(120);
        inviteTable.getColumnModel().getColumn(6).setPreferredWidth(170);
        inviteTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        applyFilter();

        checkIn.addActionListener(e -> act(Command.CHECK_IN, null));
        checkOut.addActionListener(e -> act(Command.CHECK_OUT, "Trả chỗ và kết thúc lượt sử dụng?"));
        cancel.addActionListener(e -> act(Command.CANCEL_BOOKING, "Hủy lượt đặt đã chọn?"));
        editCompanions.addActionListener(e -> {
            Booking b = UiKit.selected(table, model);
            if (b != null) {
                editCompanions(b);
            }
        });
        accept.addActionListener(e -> {
            Booking b = UiKit.selected(inviteTable, invites);
            if (b != null) {
                respondInvite(b.getId(), true);
            }
        });
        decline.addActionListener(e -> {
            Booking b = UiKit.selected(inviteTable, invites);
            if (b != null && UiKit.confirm(this, "Từ chối đi cùng " + b.getFullName() + "?\n\n" + b.getId() + ": "
                    + b.describe())) {
                respondInvite(b.getId(), false);
            }
        });
        onlyLive.addActionListener(e -> applyFilter());
        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> reload());

        JPanel toolbar = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        checkIn.setFont(UiKit.font(Font.BOLD, 13f));
        left.add(checkIn);
        left.add(checkOut);
        left.add(cancel);
        left.add(editCompanions);
        left.add(refresh);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(countLabel);
        right.add(onlyLive);
        toolbar.add(left, BorderLayout.WEST);
        toolbar.add(right, BorderLayout.EAST);

        JLabel rules = UiKit.label("<html><b>Quy định:</b> tối đa " + Protocol.MAX_BOOKINGS_PER_DAY
                + " ca/ngày, đặt trước tối đa " + Reputation.Level.GOOD.getDaysAhead() + " ngày ("
                + Protocol.BOOKING_DAYS_AHEAD + " ngày khi đạt " + Reputation.MAX
                + " điểm uy tín). Check-in trong khoảng "
                + Protocol.CHECKIN_EARLY_MINUTES + " phút trước đến " + Protocol.CHECKIN_LATE_MINUTES
                + " phút sau giờ bắt đầu ca; quá hạn hệ thống tự hủy để nhường chỗ và <b>trừ "
                + Reputation.NO_SHOW_PENALTY + " điểm uy tín</b> (đặt cả bàn nhiều chỗ: tới "
                + Reputation.NO_SHOW_MAX_PENALTY + " điểm). Hủy dưới "
                + Reputation.LATE_CANCEL_MINUTES + " phút trước ca: trừ "
                + Reputation.LATE_CANCEL_PENALTY + " điểm. Check-in đúng hẹn: cộng "
                + Reputation.CHECK_IN_REWARD + " điểm. Đặt cả bàn nhiều chỗ phải khai báo người đi cùng "
                + "(tổng số người ít nhất: bàn đôi " + Protocol.minPeopleForTable(2) + ", bàn 4 người "
                + Protocol.minPeopleForTable(4) + ", bàn 6 người " + Protocol.minPeopleForTable(6) + ", phòng 8 người "
                + Protocol.minPeopleForTable(8) + "); người đi cùng phải bấm <b>Đồng ý</b> thì bàn mới đủ người để "
                + "check-in (sau " + Protocol.COMPANION_REMIND_MINUTES + " phút chưa đủ thì nhắc, sau "
                + Protocol.COMPANION_CONFIRM_MINUTES + " phút thì <b>tự hủy</b>), đã đồng ý mà cả nhóm không đến bị trừ "
                + Reputation.COMPANION_NO_SHOW_PENALTY + " điểm.</html>", Font.PLAIN, 12f, UiKit.MUTED);

        JComponent center = new JScrollPane(table);
        if (!ctx.isAdmin()) {
            JPanel invitePanel = new JPanel(new BorderLayout(0, 6));
            JPanel bar = new JPanel(new BorderLayout());
            JPanel bl = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            bl.add(UiKit.label("Bàn bạn được mời đi cùng", Font.BOLD, 13f, UiKit.PRIMARY));
            accept.setFont(UiKit.font(Font.BOLD, 13f));
            bl.add(accept);
            bl.add(decline);
            bar.add(bl, BorderLayout.WEST);
            bar.add(inviteCount, BorderLayout.EAST);
            invitePanel.add(bar, BorderLayout.NORTH);
            invitePanel.add(new JScrollPane(inviteTable), BorderLayout.CENTER);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, invitePanel);
            split.setResizeWeight(0.6);
            split.setBorder(null);
            center = split;
        }

        add(toolbar, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(rules, BorderLayout.SOUTH);
        updateButtons();
    }

    /** "Đủ người" / "1/2 đã xác nhận" / "Thiếu người". */
    private static String groupLabel(Booking b) {
        if (b.isCompanionsReady()) {
            return "Đủ người";
        }
        String deadline = b.getStatus() == BookingStatus.ACTIVE && b.getCompanionDeadline() != null
                ? " - hạn " + Fmt.time(b.getCompanionDeadline()) : "";
        if (b.isCompanionsShort()) {
            return "Thiếu người (" + b.getActiveCompanionCount() + "/" + b.getCompanionsNeeded() + ")" + deadline;
        }
        return b.getAcceptedCompanionCount() + "/" + b.getCompanionsNeeded() + " đã xác nhận" + deadline;
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
            if (firstLoad) { // vừa đăng nhập: nhắc bàn đang thiếu người / quá 30 phút chưa đủ người xác nhận
                for (Booking b : list) {
                    if (b.needsCompanionAction()) {
                        Toast.show(this, "Bàn " + b.getSeatId() + " (" + b.getId() + ") chưa đủ người xác nhận đi cùng - "
                                + "sẽ tự hủy lúc " + Fmt.time(b.getCompanionDeadline()) + " nếu vẫn chưa đủ. Hãy thêm / "
                                + "thay người (Sửa người đi cùng) hoặc hủy lượt đặt.", UiKit.WARNING);
                    }
                }
            }

            List<Booking> inv = r.getList("invites");
            if (inv != null) {
                Booking isel = UiKit.selected(inviteTable, invites);
                invites.setRows(inv);
                if (isel != null) {
                    UiKit.reselect(inviteTable, invites, b -> b.getId().equals(isel.getId()));
                }
                long waiting = inv.stream().filter(MyBookingsPanel::awaitingMe).count();
                inviteCount.setText(waiting == 0 ? "" : waiting + " lời mời đang chờ bạn trả lời  ");
                if (firstLoad && waiting > 0) {
                    Toast.show(this, "Bạn có " + waiting + " lời mời đi cùng đang chờ xác nhận - xem ở tab "
                            + "\"Lịch đặt của tôi\".", UiKit.PRIMARY);
                }
            }
            firstLoad = false;
            updateButtons();
        }, null);
    }

    private static boolean awaitingMe(Booking b) {
        return b.getStatus() == BookingStatus.ACTIVE && b.getMyCompanionStatus() == Companion.Status.PENDING;
    }

    /** Trả lời lời mời đi cùng (gọi từ bảng bên dưới hoặc từ hộp hỏi khi vừa nhận lời mời). */
    public void respondInvite(String bookingId, boolean yes) {
        UiKit.call(this, ctx.conn(), Message.request(Command.RESPOND_COMPANION).put("bookingId", bookingId)
                .put("accept", yes), r -> {
                    Toast.show(this, r.getText(), yes ? UiKit.SUCCESS : UiKit.INFO);
                    reload();
                }, this::reload);
    }

    private void applyFilter() {
        filterLive(table);
        filterLive(inviteTable);
    }

    private void filterLive(JTable t) {
        @SuppressWarnings("unchecked")
        TableRowSorter<ListTableModel<Booking>> sorter = (TableRowSorter<ListTableModel<Booking>>) t.getRowSorter();
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
            } else if (!b.isCompanionsReady()) {
                tip = "Bàn cần đủ người đã bấm Đồng ý đi cùng mới check-in được";
            }
        }
        checkIn.setEnabled(canCheckIn);
        checkIn.setToolTipText(tip);
        checkOut.setEnabled(b != null && b.getStatus() == BookingStatus.CHECKED_IN);
        cancel.setEnabled(b != null && b.getStatus() == BookingStatus.ACTIVE);
        boolean canEdit = b != null && b.getStatus() == BookingStatus.ACTIVE && !b.getCompanions().isEmpty()
                && b.getCompanionsNeeded() > 0;
        editCompanions.setEnabled(canEdit);
        editCompanions.setToolTipText(b != null && b.needsCompanionAction()
                ? "Bàn chưa đủ người xác nhận - thêm / thay người đi cùng" : null);

        Booking inv = UiKit.selected(inviteTable, invites);
        boolean open = inv != null && inv.getStatus() == BookingStatus.ACTIVE
                && inv.getMyCompanionStatus() != Companion.Status.DECLINED;
        accept.setEnabled(open && inv.getMyCompanionStatus() != Companion.Status.ACCEPTED);
        decline.setEnabled(open);
    }

    /** Mở hộp sửa người đi cùng / hủy cho lượt {@code bookingId} (từ lời nhắc quá 30 phút): lấy dữ liệu mới nhất trước. */
    public void handleReminder(String bookingId, boolean cancelIt) {
        UiKit.call(this, ctx.conn(), Message.request(Command.GET_MY_BOOKINGS), r -> {
            List<Booking> list = r.getList("bookings");
            model.setRows(list);
            Booking b = list.stream().filter(x -> x.getId().equals(bookingId)).findFirst().orElse(null);
            if (b == null || b.getStatus() != BookingStatus.ACTIVE) {
                UiKit.info(this, "Lượt đặt " + bookingId + " không còn chờ check-in.");
                return;
            }
            UiKit.reselect(table, model, x -> x.getId().equals(bookingId));
            if (cancelIt) {
                act(Command.CANCEL_BOOKING, "Hủy lượt đặt đã chọn?");
            } else {
                editCompanions(b);
            }
        });
    }

    /** Sửa danh sách người đi cùng của một lượt đặt cả bàn (VD khi có người từ chối / chưa xác nhận). */
    private void editCompanions(Booking b) {
        StringBuilder now = new StringBuilder();
        StringBuilder keep = new StringBuilder();
        for (Companion m : b.getCompanions()) {
            now.append("<br>• ").append(UiKit.html(m.toString()));
            if (!m.isDeclined()) {
                keep.append(keep.length() == 0 ? "" : "\n").append(m.getIdent());
            }
        }
        String header = "<b>" + UiKit.html(b.getId() + ": " + b.describe()) + "</b><br>Danh sách hiện tại:" + now
                + (b.isCompanionsShort() ? "<br><font color='#C93C3C'>Có người đã từ chối nên bàn đang thiếu người - "
                + "hãy thêm người khác.</font>" : "")
                + (b.getCompanionDeadline() != null ? "<br><font color='#C93C3C'>Nếu đến " + Fmt.time(b.getCompanionDeadline())
                + " (" + Protocol.COMPANION_CONFIRM_MINUTES + " phút sau lúc đặt) vẫn chưa đủ người đồng ý, lượt đặt sẽ tự "
                + "hủy - có thể thay người chưa trả lời bằng người khác, nhưng hạn không lùi lại.</font>" : "")
                + "<br><i>Người giữ lại không phải xác nhận lại; người mới sẽ nhận lời mời.</i>";
        CompanionDialog.askAndSend(this, ctx, "Sửa người đi cùng - bàn " + b.getSeatId(), header, b.getSeatCapacity(),
                null, keep.toString(),
                ids -> Message.request(Command.UPDATE_COMPANIONS).put("bookingId", b.getId()).put("companions", ids),
                resp -> {
                    Toast.show(this, resp.getText(), UiKit.SUCCESS);
                    reload();
                }, this::reload);
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
