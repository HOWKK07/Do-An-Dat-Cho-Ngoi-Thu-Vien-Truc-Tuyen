package library.client.ui;

import library.common.BookingStatus;
import library.common.Command;
import library.common.Fmt;
import library.common.Message;
import library.common.Protocol;
import library.common.Seat;
import library.common.SeatMap;
import library.common.SeatState;
import library.common.SeatView;
import library.common.TimeSlot;
import library.common.Zone;
import library.client.ui.admin.AdminBookingDialog;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Màn hình chính: chọn ngày + ca, xem sơ đồ chỗ ngồi theo thời gian thực và đặt chỗ. */
public class BookingPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String ALL_ZONES = "Tất cả khu vực";
    private static final int COLUMNS = 9;

    private final ClientContext ctx;
    private final JComboBox<LocalDate> dateBox = new JComboBox<>();
    private final JComboBox<TimeSlot> slotBox = new JComboBox<>(TimeSlot.ALL.toArray(new TimeSlot[0]));
    private final JComboBox<Object> zoneBox = new JComboBox<>(new Object[]{ALL_ZONES});
    private final JLabel summary = new JLabel(" ");
    private final JLabel notice = new JLabel();
    private final JPanel mapPanel = new JPanel();
    private final Timer refreshTimer;
    private SeatMap current;
    private boolean adjusting;

    public BookingPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(12, 12, 12, 12));
        setBackground(UiKit.BG);

        refreshTimer = ctx.timer(150, e -> loadMap()); // gom nhiều sự kiện liên tiếp thành 1 lần tải
        refreshTimer.setRepeats(false);

        dateBox.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof LocalDate) {
                    setText(Fmt.relativeDayLabel((LocalDate) value));
                }
                return this;
            }
        });
        resetDates();

        dateBox.addActionListener(e -> {
            if (!adjusting) {
                requestRefresh();
            }
        });
        slotBox.addActionListener(e -> {
            if (!adjusting) {
                requestRefresh();
            }
        });
        zoneBox.addActionListener(e -> {
            if (!adjusting && current != null) {
                render(current);
            }
        });

        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> loadMap());
        JButton today = new JButton("Ca hiện tại");
        today.setToolTipText("Chuyển về ca gần nhất còn đặt được");
        today.addActionListener(e -> {
            resetDates();
            requestRefresh();
        });

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setOpaque(false);
        toolbar.add(UiKit.label("Ngày:", Font.BOLD, 13f, null));
        toolbar.add(dateBox);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(UiKit.label("Ca học:", Font.BOLD, 13f, null));
        toolbar.add(slotBox);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(UiKit.label("Khu vực:", Font.BOLD, 13f, null));
        zoneBox.setPrototypeDisplayValue("Khu A - Khu đọc yên tĩnh xx");
        toolbar.add(zoneBox);
        toolbar.add(refresh);
        toolbar.add(today);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        legend.setOpaque(false);
        addLegend(legend, SeatButton.legendSwatch(SeatButton.AVAILABLE_FILL, SeatButton.AVAILABLE_LINE, false), "Còn trống");
        addLegend(legend, SeatButton.legendSwatch(SeatButton.BOOKED_FILL, SeatButton.BOOKED_LINE, false), "Đã có người đặt");
        addLegend(legend, SeatButton.legendSwatch(SeatButton.MINE_FILL, SeatButton.MINE_LINE, false), "Chỗ của bạn");
        addLegend(legend, SeatButton.legendSwatch(SeatButton.OFF_FILL, SeatButton.OFF_LINE, false), "Bảo trì");
        addLegend(legend, SeatButton.legendSwatch(SeatButton.SHARE_FILL, SeatButton.SHARE_LINE, false), "Đặt lẻ / ngồi ghép");
        addLegend(legend, SeatButton.legendSwatch(null, null, true), "Có ổ cắm điện");

        JPanel infoRow = new JPanel(new BorderLayout());
        infoRow.setOpaque(false);
        summary.setFont(UiKit.font(Font.PLAIN, 13f));
        infoRow.add(summary, BorderLayout.NORTH);
        legend.setBorder(UiKit.padding(6, 0, 0, 0));
        infoRow.add(legend, BorderLayout.SOUTH);

        notice.setOpaque(true);
        notice.setBackground(new java.awt.Color(0xFFF4E5));
        notice.setForeground(UiKit.WARNING);
        notice.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new java.awt.Color(0xF5D9A8)), UiKit.padding(6, 10, 6, 10)));
        notice.setVisible(false);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        toolbar.setAlignmentX(LEFT_ALIGNMENT);
        infoRow.setAlignmentX(LEFT_ALIGNMENT);
        notice.setAlignmentX(LEFT_ALIGNMENT);
        top.add(toolbar);
        top.add(Box.createVerticalStrut(10));
        top.add(infoRow);
        top.add(Box.createVerticalStrut(6));
        top.add(notice);

        mapPanel.setLayout(new BoxLayout(mapPanel, BoxLayout.Y_AXIS));
        mapPanel.setBackground(UiKit.BG);
        JScrollPane scroll = new JScrollPane(mapPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(UiKit.BG);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        ctx.addZoneListener(this::onZonesReloaded);
    }

    private static void addLegend(JPanel p, JComponent swatch, String text) {
        p.add(swatch);
        p.add(UiKit.label(text, Font.PLAIN, 12f, UiKit.MUTED));
        p.add(Box.createHorizontalStrut(6));
    }

    /** Đặt lại danh sách ngày (hôm nay + 6 ngày) và chọn ca gần nhất còn đặt được. */
    private void resetDates() {
        adjusting = true;
        LocalDate today = LocalDate.now();
        dateBox.removeAllItems();
        int days = ctx.isAdmin() ? Protocol.ADMIN_BOOKING_DAYS_AHEAD : Protocol.BOOKING_DAYS_AHEAD;
        for (int i = 0; i < days; i++) {
            dateBox.addItem(today.plusDays(i));
        }
        TimeSlot next = ctx.isAdmin() ? TimeSlot.firstNotEnded(LocalTime.now()) : TimeSlot.firstBookable(LocalTime.now());
        if (next != null) {
            dateBox.setSelectedIndex(0);
            slotBox.setSelectedItem(next);
        } else { // hết ca hôm nay → mặc định ca 1 ngày mai
            dateBox.setSelectedIndex(1);
            slotBox.setSelectedIndex(0);
        }
        adjusting = false;
    }

    private void onZonesReloaded() {
        adjusting = true;
        Object selected = zoneBox.getSelectedItem();
        String selectedId = selected instanceof Zone ? ((Zone) selected).getId() : null;
        zoneBox.removeAllItems();
        zoneBox.addItem(ALL_ZONES);
        for (Zone z : ctx.zones()) {
            zoneBox.addItem(z);
            if (z.getId().equals(selectedId)) {
                zoneBox.setSelectedItem(z);
            }
        }
        adjusting = false;
        requestRefresh();
    }

    public void requestRefresh() {
        refreshTimer.restart();
    }

    /** Sự kiện từ server: chỉ tải lại nếu đúng ngày/ca đang xem. */
    public void onSeatsChanged(LocalDate date, int slot) {
        if (date == null || (date.equals(dateBox.getSelectedItem()) && slot == selectedSlot())) {
            requestRefresh();
        }
    }

    private int selectedSlot() {
        TimeSlot s = (TimeSlot) slotBox.getSelectedItem();
        return s == null ? 0 : s.getIndex();
    }

    private void loadMap() {
        LocalDate date = (LocalDate) dateBox.getSelectedItem();
        int slot = selectedSlot();
        if (date == null || slot == 0) {
            return;
        }
        UiKit.callQuiet(ctx.conn(), Message.request(Command.GET_SEAT_MAP).put("date", date).put("slot", slot),
                r -> {
                    SeatMap map = r.get("map");
                    if (map.getDate().equals(dateBox.getSelectedItem()) && map.getSlot() == selectedSlot()) {
                        render(map);
                    }
                },
                err -> summary.setText("<html><font color='#C93C3C'>Không tải được sơ đồ chỗ: "
                        + UiKit.html(err) + "</font></html>"));
    }

    private void render(SeatMap map) {
        current = map;
        mapPanel.removeAll();
        Object zsel = zoneBox.getSelectedItem();
        String onlyZone = zsel instanceof Zone ? ((Zone) zsel).getId() : null;

        Map<String, List<SeatView>> byZone = new LinkedHashMap<>();
        for (SeatView v : map.getSeats()) {
            byZone.computeIfAbsent(v.getSeat().getZoneId(), k -> new ArrayList<>()).add(v);
        }
        int total = 0;
        int free = 0;
        for (Zone z : map.getZones()) {
            if (onlyZone != null && !onlyZone.equals(z.getId())) {
                continue;
            }
            List<SeatView> seats = byZone.getOrDefault(z.getId(), new ArrayList<>());
            int zoneFree = 0;
            for (SeatView v : seats) {
                if (v.getState() == SeatState.AVAILABLE || v.getState() == SeatState.PARTIAL) {
                    zoneFree++;
                }
            }
            total += seats.size();
            free += zoneFree;
            mapPanel.add(zoneCard(z, seats, zoneFree, map.isOpen()));
            mapPanel.add(Box.createVerticalStrut(10));
        }
        if (map.getZones().isEmpty()) {
            mapPanel.add(UiKit.label("Thư viện chưa có khu vực nào.", Font.PLAIN, 14f, UiKit.MUTED));
        }
        mapPanel.add(Box.createVerticalGlue());

        TimeSlot ts = TimeSlot.of(map.getSlot());
        summary.setText("<html><b>" + Fmt.dayLabel(map.getDate()) + "</b> &nbsp;•&nbsp; " + ts.getLabel()
                + " &nbsp;|&nbsp; Còn trống <b>" + free + "/" + total + "</b> chỗ"
                + (ctx.isAdmin()
                ? " &nbsp;|&nbsp; Quản trị viên: đặt <b>không giới hạn</b>"
                : " &nbsp;|&nbsp; Uy tín <b><font color='" + hex(UiKit.levelColor(map.getReputationLevel())) + "'>"
                + map.getReputation() + " (" + map.getReputationLevel().getLabel() + ")</font></b>"
                + " &nbsp;|&nbsp; Đã đặt <b>" + map.getMyBookingsThatDay() + "/" + map.getMaxBookingsPerDay()
                + "</b> ca trong ngày")
                + "</html>");
        // Đóng đặt chỗ → báo lý do; đang mở ngồi ghép → báo bàn nào đang cho đặt lẻ
        notice.setText(!map.isOpen() ? map.getClosedReason() + " Hãy chọn ngày hoặc ca khác."
                : map.getShareNote() != null ? map.getShareNote() : "");
        notice.setVisible(!map.isOpen() || map.getShareNote() != null);
        mapPanel.revalidate();
        mapPanel.repaint();
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private JPanel zoneCard(Zone z, List<SeatView> seats, int zoneFree, boolean open) {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(UiKit.CARD);
        card.setBorder(UiKit.cardBorder());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(UiKit.label("<html><b>" + UiKit.html(z.getDisplayName()) + "</b> &nbsp;<font color='#6B7280'>Tầng "
                + z.getFloor() + " • " + seats.size() + " chỗ • còn trống " + zoneFree + "</font></html>",
                Font.PLAIN, 14f, UiKit.PRIMARY), BorderLayout.NORTH);
        if (z.getDescription() != null && !z.getDescription().isEmpty()) {
            header.add(UiKit.label(z.getDescription(), Font.PLAIN, 12f, UiKit.MUTED), BorderLayout.SOUTH);
        }
        card.add(header, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 8, 8));
        grid.setOpaque(false);
        for (SeatView v : seats) {
            grid.add(new SeatButton(v, open, z.getDisplayName(), v.getBookedBy(), this::onSeatClicked));
        }
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        if (seats.isEmpty()) {
            wrap.add(UiKit.label("Khu vực này chưa có chỗ ngồi.", Font.ITALIC, 12f, UiKit.MUTED));
        } else {
            wrap.add(grid);
        }
        card.add(wrap, BorderLayout.CENTER);
        card.setAlignmentX(LEFT_ALIGNMENT);
        Dimension pref = card.getPreferredSize();
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        return card;
    }

    // ------------------------------------------------------------------ Thao tác trên chỗ

    private void onSeatClicked(SeatView v) {
        SeatMap map = current;
        if (map == null) {
            return;
        }
        Seat s = v.getSeat();
        Zone z = ctx.findZone(s.getZoneId());
        String place = s.getId() + " — " + (z != null ? z.getDisplayName() : s.getZoneId());
        String where = "Chỗ " + place;
        String when = Fmt.dayLabel(map.getDate()) + "\n" + TimeSlot.of(map.getSlot()).getLabel();

        switch (v.getState()) {
            case AVAILABLE:
                if (!map.isOpen()) {
                    UiKit.info(this, map.getClosedReason());
                    return;
                }
                if (ctx.isAdmin()) {
                    adminBook(s, place, map, v.isShareOpen(), false);
                    return;
                }
                if (v.isShareOpen()) { // bàn đang mở ngồi ghép: chọn đặt cả bàn hay chỉ 1 chỗ
                    int pick = JOptionPane.showOptionDialog(this, "<html><b>" + UiKit.html(where) + "</b><br>"
                                    + UiKit.html(when).replace("\n", "<br>") + "<br><br>Khu chỗ đơn đã kín nên bàn này "
                                    + "đang mở cho <b>đặt lẻ từng chỗ</b> (ngồi ghép với người khác).<br>"
                                    + "Bạn muốn đặt thế nào?</html>", "Đặt chỗ", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Đặt 1 chỗ (ngồi ghép)",
                                    "Đặt cả bàn (" + s.getCapacity() + " người)", "Hủy"}, "Đặt 1 chỗ (ngồi ghép)");
                    if (pick == 0 || pick == 1) {
                        sendBooking(s, map, pick == 0);
                    }
                    return;
                }
                if (UiKit.confirm(this, "Xác nhận đặt chỗ?\n\n" + where + "\n" + s.getType().getLabel() + " • "
                        + s.getCapacity() + " người" + (s.isHasOutlet() ? " • có ổ cắm điện" : "") + "\n" + when)) {
                    sendBooking(s, map, false);
                }
                break;

            case PARTIAL: // bàn đang có người ngồi ghép, còn chỗ
                if (!map.isOpen()) {
                    UiKit.info(this, map.getClosedReason());
                    return;
                }
                if (ctx.isAdmin()) {
                    int a = JOptionPane.showOptionDialog(this, where + "\n" + when + "\nĐang ngồi ghép "
                                    + v.getSharedTaken() + "/" + s.getCapacity() + ":\n" + listHolders(v.getBookedBy())
                                    + "\n\nCheck-in / hủy từng lượt ở tab \"QT: Quản lý đặt chỗ\".",
                            "Bàn ngồi ghép", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                            new Object[]{"Đặt 1 chỗ ghép", "Đóng"}, "Đóng");
                    if (a == 0) {
                        adminBook(s, place, map, true, true);
                    }
                    return;
                }
                if (UiKit.confirm(this, "Đặt 1 chỗ ngồi ghép?\n\n" + where + "\n" + when + "\nBàn đang có "
                        + v.getSharedTaken() + "/" + s.getCapacity() + " người ngồi ghép, còn " + v.getFreePlaces()
                        + " chỗ.")) {
                    sendBooking(s, map, true);
                }
                break;

            case MINE:
                if (v.getBookingStatus() == BookingStatus.CHECKED_IN) {
                    UiKit.info(this, "Bạn đang sử dụng chỗ " + place
                            + ".\nKhi rời đi, hãy vào \"Lịch đặt của tôi\" và bấm \"Trả chỗ\".");
                    return;
                }
                int choice = JOptionPane.showOptionDialog(this,
                        "Bạn đã đặt chỗ " + place + (v.getMyPlace() > 0 ? " (ngồi ghép, vị trí #" + v.getMyPlace() + ")" : "")
                                + "\n" + when + "\nMã đặt chỗ: " + v.getBookingId(),
                        "Chỗ của bạn", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                        new Object[]{"Hủy lượt đặt", "Đóng"}, "Đóng");
                if (choice == 0 && UiKit.confirm(this, "Hủy lượt đặt " + v.getBookingId() + "?")) {
                    UiKit.call(this, ctx.conn(), Message.request(Command.CANCEL_BOOKING)
                            .put("bookingId", v.getBookingId()), r -> {
                        Toast.show(this, r.getText(), UiKit.INFO);
                        requestRefresh();
                    });
                }
                break;

            case BOOKED:
                if (ctx.isAdmin() && v.getBookingId() != null) {
                    // Chỉ hiện nút Check-in khi đã đến ca (từ 15 phút trước giờ bắt đầu) và ca chưa kết thúc
                    boolean started = TimeSlot.of(map.getSlot()).isCheckInStarted(map.getDate(), LocalDateTime.now());
                    String extra = v.getBookingStatus() == BookingStatus.ACTIVE && started ? "Check-in"
                            : v.getBookingStatus() == BookingStatus.CHECKED_IN ? "Xác nhận trả chỗ" : null;
                    Object[] options = extra != null ? new Object[]{extra, "Hủy lượt đặt này", "Đóng"}
                            : new Object[]{"Hủy lượt đặt này", "Đóng"};
                    int c = JOptionPane.showOptionDialog(this,
                            where + "\n" + when + "\nNgười đặt: " + v.getBookedBy() + "\nMã: " + v.getBookingId()
                                    + " (" + v.getBookingStatus().getLabel() + ")",
                            "Thông tin lượt đặt", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                            options, "Đóng");
                    if (extra != null && c == 0) {
                        Command cmd = v.getBookingStatus() == BookingStatus.ACTIVE ? Command.ADMIN_CHECK_IN
                                : Command.ADMIN_CHECK_OUT;
                        UiKit.call(this, ctx.conn(), Message.request(cmd).put("bookingId", v.getBookingId()), r -> {
                            Toast.show(this, r.getText(), UiKit.SUCCESS);
                            requestRefresh();
                        });
                        return;
                    }
                    if (c == (extra != null ? 1 : 0)) {
                        String reason = JOptionPane.showInputDialog(this, "Lý do hủy (sẽ gửi cho sinh viên):",
                                "Hủy lượt đặt", JOptionPane.QUESTION_MESSAGE);
                        if (reason != null) {
                            UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_CANCEL_BOOKING)
                                    .put("bookingId", v.getBookingId()).put("reason", reason), r -> {
                                Toast.show(this, r.getText(), UiKit.INFO);
                                requestRefresh();
                            });
                        }
                    }
                } else if (ctx.isAdmin() && v.getBookedBy() != null) { // bàn ngồi ghép đã đủ người
                    UiKit.info(this, where + "\n" + when + "\nĐã đủ " + s.getCapacity() + " người ngồi ghép:\n"
                            + listHolders(v.getBookedBy()) + "\n\nCheck-in / hủy từng lượt ở tab \"QT: Quản lý đặt chỗ\".");
                } else {
                    UiKit.info(this, where + " đã có người đặt trong ca này.\nVui lòng chọn chỗ màu xanh lá"
                            + (map.getShareNote() != null ? " hoặc bàn màu vàng (đặt lẻ)." : "."));
                }
                break;

            case MAINTENANCE:
                UiKit.info(this, where + " đang tạm ngừng phục vụ"
                        + (s.getNote() != null ? ": " + s.getNote() : "") + ".");
                break;

            default:
                break;
        }
    }

    private void sendBooking(Seat s, SeatMap map, boolean onePlace) {
        UiKit.call(this, ctx.conn(), Message.request(Command.BOOK_SEAT).put("seatId", s.getId())
                        .put("date", map.getDate()).put("slot", map.getSlot()).put("onePlace", onePlace),
                r -> {
                    Toast.show(this, r.getText(), UiKit.SUCCESS);
                    requestRefresh();
                },
                this::requestRefresh);
    }

    private void adminBook(Seat s, String place, SeatMap map, boolean shareOpen, boolean placeOnly) {
        Message req = AdminBookingDialog.show(this, s, place, map.getDate(), TimeSlot.of(map.getSlot()),
                shareOpen, placeOnly);
        if (req != null) {
            UiKit.call(this, ctx.conn(), req, r -> {
                Toast.show(this, r.getText(), UiKit.SUCCESS);
                requestRefresh();
            }, this::requestRefresh);
        }
    }

    /** "#1 A; #2 B" → mỗi người một dòng. */
    private static String listHolders(String holders) {
        return holders == null ? "" : "  " + holders.replace("; ", "\n  ");
    }
}
