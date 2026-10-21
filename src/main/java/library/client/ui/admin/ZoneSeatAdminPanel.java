package library.client.ui.admin;

import library.client.ui.ClientContext;
import library.client.ui.ListTableModel;
import library.client.ui.Toast;
import library.client.ui.UiKit;
import library.common.Command;
import library.common.Message;
import library.common.Seat;
import library.common.SeatType;
import library.common.Zone;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.stream.Collectors;

/** Quản trị nội dung: khu vực và chỗ ngồi (thêm / sửa / xóa / bảo trì). */
public class ZoneSeatAdminPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final ListTableModel<Zone> zoneModel = new ListTableModel<Zone>()
            .col("Mã", Zone::getId)
            .col("Tên khu vực", Zone::getName)
            .col("Tầng", Integer.class, Zone::getFloor)
            .col("Số chỗ", Integer.class, this::seatCount)
            .col("Mô tả", Zone::getDescription);
    private final ListTableModel<Seat> seatModel = new ListTableModel<Seat>()
            .col("Mã chỗ", Seat::getId)
            .col("Loại", Seat::getType)
            .col("Sức chứa", Integer.class, Seat::getCapacity)
            .col("Ổ cắm điện", Boolean.class, Seat::isHasOutlet)
            .col("Trạng thái", s -> s.isActive() ? "Hoạt động" : "Bảo trì: " + (s.getNote() == null ? "" : s.getNote()));
    private final JTable zoneTable = UiKit.table(zoneModel);
    private final JTable seatTable = UiKit.table(seatModel);
    private final JButton toggleSeat = new JButton("Bảo trì / Mở lại");

    public ZoneSeatAdminPanel(ClientContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        setBorder(UiKit.padding(10, 10, 10, 10));

        zoneTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        zoneTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        zoneTable.getColumnModel().getColumn(4).setPreferredWidth(260);
        zoneTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSeatsOfSelectedZone();
            }
        });

        JButton addZone = new JButton("Thêm khu vực");
        addZone.addActionListener(e -> editZone(null));
        JButton editZone = new JButton("Sửa");
        editZone.addActionListener(e -> {
            Zone z = UiKit.selected(zoneTable, zoneModel);
            if (z != null) {
                editZone(z);
            }
        });
        JButton deleteZone = new JButton("Xóa");
        deleteZone.addActionListener(e -> deleteZone());

        JButton addSeat = new JButton("Thêm chỗ");
        addSeat.addActionListener(e -> editSeat(null));
        JButton addMany = new JButton("Thêm nhiều chỗ");
        addMany.addActionListener(e -> addManySeats());
        JButton editSeat = new JButton("Sửa");
        editSeat.addActionListener(e -> {
            Seat s = UiKit.selected(seatTable, seatModel);
            if (s != null) {
                editSeat(s);
            }
        });
        JButton deleteSeat = new JButton("Xóa");
        deleteSeat.addActionListener(e -> deleteSeat());
        toggleSeat.addActionListener(e -> toggleSeat());

        JPanel zonePanel = section("Khu vực", zoneTable, addZone, editZone, deleteZone);
        JPanel seatPanel = section("Chỗ ngồi của khu vực đang chọn", seatTable, addSeat, addMany, editSeat,
                deleteSeat, toggleSeat);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, zonePanel, seatPanel);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        ctx.addZoneListener(this::refreshTables);
    }

    private static JPanel section(String title, JTable table, JButton... buttons) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), UiKit.padding(4, 4, 4, 4)));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : buttons) {
            bar.add(b);
        }
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    private int seatCount(Zone z) {
        return (int) ctx.seats().stream().filter(s -> s.getZoneId().equals(z.getId())).count();
    }

    private void refreshTables() {
        Zone sel = UiKit.selected(zoneTable, zoneModel);
        zoneModel.setRows(ctx.zones());
        if (sel != null) {
            UiKit.reselect(zoneTable, zoneModel, z -> z.getId().equals(sel.getId()));
        } else if (zoneModel.getRowCount() > 0) {
            zoneTable.setRowSelectionInterval(0, 0);
        }
        showSeatsOfSelectedZone();
    }

    private void showSeatsOfSelectedZone() {
        Zone z = UiKit.selected(zoneTable, zoneModel);
        Seat sel = UiKit.selected(seatTable, seatModel);
        List<Seat> seats = z == null ? java.util.Collections.emptyList()
                : ctx.seats().stream().filter(s -> s.getZoneId().equals(z.getId())).collect(Collectors.toList());
        seatModel.setRows(seats);
        if (sel != null) {
            UiKit.reselect(seatTable, seatModel, s -> s.getId().equals(sel.getId()));
        }
    }

    // ------------------------------------------------------------------ Khu vực

    private void editZone(Zone existing) {
        boolean isNew = existing == null;
        JTextField id = new JTextField(isNew ? "" : existing.getId(), 6);
        id.setEnabled(isNew);
        JTextField name = new JTextField(isNew ? "" : existing.getName(), 24);
        JSpinner floor = new JSpinner(new SpinnerNumberModel(isNew ? 1 : existing.getFloor(), 0, 50, 1));
        JTextField desc = new JTextField(isNew ? "" : existing.getDescription(), 30);
        if (!UiKit.form(this, isNew ? "Thêm khu vực" : "Sửa khu vực " + existing.getId(),
                "Mã khu (VD: E)", id, "Tên khu vực", name, "Tầng", floor, "Mô tả", desc)) {
            return;
        }
        Zone z = new Zone(id.getText().trim().toUpperCase(), name.getText(), (Integer) floor.getValue(), desc.getText());
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_SAVE_ZONE).put("zone", z).put("isNew", isNew), r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            ctx.reloadZones();
        });
    }

    private void deleteZone() {
        Zone z = UiKit.selected(zoneTable, zoneModel);
        if (z == null) {
            return;
        }
        if (!UiKit.confirm(this, "Xóa " + z.getDisplayName() + "?\n\nToàn bộ " + seatCount(z)
                + " chỗ ngồi trong khu sẽ bị xóa và các lượt đặt còn hiệu lực sẽ bị hủy (người đặt sẽ được thông báo).")) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_DELETE_ZONE).put("zoneId", z.getId()), r -> {
            Toast.show(this, r.getText(), UiKit.INFO);
            ctx.reloadZones();
        });
    }

    // ------------------------------------------------------------------ Chỗ ngồi

    private JComboBox<Zone> zoneCombo(String selectedId) {
        JComboBox<Zone> box = new JComboBox<>(ctx.zones().toArray(new Zone[0]));
        for (Zone z : ctx.zones()) {
            if (z.getId().equals(selectedId)) {
                box.setSelectedItem(z);
            }
        }
        return box;
    }

    private void editSeat(Seat existing) {
        boolean isNew = existing == null;
        Zone current = UiKit.selected(zoneTable, zoneModel);
        if (ctx.zones().isEmpty()) {
            UiKit.info(this, "Hãy tạo khu vực trước.");
            return;
        }
        JTextField id = new JTextField(isNew ? "" : existing.getId(), 8);
        id.setEnabled(isNew);
        JComboBox<Zone> zone = zoneCombo(isNew ? (current != null ? current.getId() : null) : existing.getZoneId());
        JComboBox<SeatType> type = new JComboBox<>(SeatType.values());
        type.setSelectedItem(isNew ? SeatType.SINGLE : existing.getType());
        JSpinner capacity = new JSpinner(new SpinnerNumberModel(isNew ? 1 : existing.getCapacity(), 1, 30, 1));
        JCheckBox outlet = new JCheckBox("Có ổ cắm điện", !isNew && existing.isHasOutlet());
        if (!UiKit.form(this, isNew ? "Thêm chỗ ngồi" : "Sửa chỗ " + existing.getId(),
                "Mã chỗ (VD: A21)", id, "Khu vực", zone, "Loại", type, "Sức chứa (người)", capacity, "", outlet)) {
            return;
        }
        Zone z = (Zone) zone.getSelectedItem();
        Seat s = new Seat(id.getText().trim().toUpperCase(), z == null ? "" : z.getId(),
                (SeatType) type.getSelectedItem(), (Integer) capacity.getValue(), outlet.isSelected());
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_SAVE_SEAT).put("seat", s).put("isNew", isNew), r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            ctx.reloadZones();
        });
    }

    private void addManySeats() {
        Zone current = UiKit.selected(zoneTable, zoneModel);
        if (current == null) {
            UiKit.info(this, "Hãy chọn khu vực ở bảng bên trái.");
            return;
        }
        JTextField prefix = new JTextField(current.getId(), 6);
        JSpinner count = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
        JComboBox<SeatType> type = new JComboBox<>(SeatType.values());
        JSpinner capacity = new JSpinner(new SpinnerNumberModel(1, 1, 30, 1));
        JCheckBox outlet = new JCheckBox("Có ổ cắm điện", true);
        if (!UiKit.form(this, "Thêm nhiều chỗ vào " + current.getDisplayName(),
                "Tiền tố mã", prefix, "Số lượng", count, "Loại", type, "Sức chứa", capacity, "", outlet)) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_ADD_SEATS)
                .put("zoneId", current.getId()).put("prefix", prefix.getText())
                .put("count", (Integer) count.getValue()).put("type", (SeatType) type.getSelectedItem())
                .put("capacity", (Integer) capacity.getValue()).put("outlet", outlet.isSelected()), r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            ctx.reloadZones();
        });
    }

    private void deleteSeat() {
        Seat s = UiKit.selected(seatTable, seatModel);
        if (s == null || !UiKit.confirm(this, "Xóa chỗ " + s.getId()
                + "?\nCác lượt đặt còn hiệu lực của chỗ này sẽ bị hủy và người đặt được thông báo.")) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_DELETE_SEAT).put("seatId", s.getId()), r -> {
            Toast.show(this, r.getText(), UiKit.INFO);
            ctx.reloadZones();
        });
    }

    private void toggleSeat() {
        Seat s = UiKit.selected(seatTable, seatModel);
        if (s == null) {
            return;
        }
        Message req = Message.request(Command.ADMIN_SET_SEAT_ACTIVE).put("seatId", s.getId());
        if (s.isActive()) {
            String reason = JOptionPane.showInputDialog(this,
                    "Lý do bảo trì chỗ " + s.getId() + " (VD: hỏng ổ điện, sửa bàn):", "Đưa vào bảo trì",
                    JOptionPane.QUESTION_MESSAGE);
            if (reason == null) {
                return;
            }
            req.put("active", false).put("reason", reason);
        } else {
            if (!UiKit.confirm(this, "Mở lại chỗ " + s.getId() + " cho sinh viên đặt?")) {
                return;
            }
            req.put("active", true);
        }
        UiKit.call(this, ctx.conn(), req, r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            ctx.reloadZones();
        });
    }
}
