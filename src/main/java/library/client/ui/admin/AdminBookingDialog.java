package library.client.ui.admin;

import library.client.ui.UiKit;
import library.common.Command;
import library.common.Fmt;
import library.common.Message;
import library.common.Seat;
import library.common.TimeSlot;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Hộp thoại đặt chỗ của quản trị viên: đặt cho chính mình, hoặc đặt hộ sinh viên KHÔNG có tài khoản
 * (sinh viên đến quầy thư viện) kèm mục ghi thông tin: họ tên, MSSV, số điện thoại, ghi chú.
 */
public final class AdminBookingDialog {

    private AdminBookingDialog() {
    }

    /** @return yêu cầu ADMIN_BOOK_SEAT cần gửi, hoặc null nếu người dùng bấm Hủy */
    public static Message show(Component parent, Seat seat, String place, LocalDate date, TimeSlot slot) {
        JRadioButton forGuest = new JRadioButton("Đặt hộ sinh viên không có tài khoản (đặt trực tiếp tại quầy)", true);
        JRadioButton forSelf = new JRadioButton("Đặt cho chính tôi (quản trị viên)");
        ButtonGroup group = new ButtonGroup();
        group.add(forGuest);
        group.add(forSelf);

        JTextField name = new JTextField(26);
        JTextField studentId = new JTextField(14);
        JTextField phone = new JTextField(14);
        JTextArea note = new JTextArea(3, 26);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        boolean today = date.equals(LocalDate.now());
        // Chưa đến ca (trước giờ mở check-in) thì không cho check-in
        boolean canCheckIn = slot.isCheckInStarted(date, LocalDateTime.now());
        JCheckBox checkIn = new JCheckBox("Sinh viên đang có mặt - check-in luôn", canCheckIn);
        checkIn.setEnabled(canCheckIn);

        JPanel info = new JPanel(new GridBagLayout());
        info.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Thông tin sinh viên"), UiKit.padding(4, 6, 6, 6)));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.NORTHWEST;
        addRow(info, c, 0, "Họ và tên *", name);
        addRow(info, c, 1, "Mã số sinh viên", studentId);
        addRow(info, c, 2, "Số điện thoại", phone);
        addRow(info, c, 3, "Ghi chú", new JScrollPane(note));

        Runnable toggle = () -> {
            boolean guest = forGuest.isSelected();
            for (Component comp : new Component[]{name, studentId, phone, note}) {
                comp.setEnabled(guest);
            }
            checkIn.setText(guest ? "Sinh viên đang có mặt - check-in luôn" : "Check-in luôn");
        };
        forGuest.addActionListener(e -> toggle.run());
        forSelf.addActionListener(e -> toggle.run());

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints p = new GridBagConstraints();
        p.gridx = 0;
        p.anchor = GridBagConstraints.WEST;
        p.fill = GridBagConstraints.HORIZONTAL;
        p.insets = new Insets(2, 0, 2, 0);
        panel.add(UiKit.label("<html><b>Chỗ " + UiKit.html(place) + "</b><br>" + Fmt.dayLabel(date) + " • "
                + slot.getLabel() + "<br>" + seat.getType().getLabel() + " • " + seat.getCapacity() + " người</html>",
                Font.PLAIN, 13f, UiKit.PRIMARY), p);
        panel.add(forGuest, p);
        panel.add(forSelf, p);
        panel.add(info, p);
        panel.add(checkIn, p);
        JLabel hint = UiKit.label("<html><div style='width:330px'>Quản trị viên đặt không giới hạn số lượt. "
                + (canCheckIn ? "Ca đã quá giờ check-in sẽ được check-in luôn."
                : "Chưa đến ca nên chưa check-in được (check-in mở từ " + Fmt.time(slot.checkInOpensAt(date))
                + (today ? "" : " ngày " + Fmt.date(date)) + "). Lượt chưa check-in sẽ tự hủy sau "
                + "15 phút kể từ giờ bắt đầu ca, hãy check-in khi sinh viên đến.") + "</div></html>",
                Font.PLAIN, 11f, UiKit.MUTED);
        panel.add(hint, p);

        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, panel, "Quản trị viên đặt chỗ " + seat.getId(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            boolean guest = forGuest.isSelected();
            String error = guest ? validate(name.getText(), studentId.getText(), phone.getText(), note.getText()) : null;
            if (error != null) {
                UiKit.error(parent, error);
                continue; // mở lại hộp thoại, giữ nguyên dữ liệu đã nhập
            }
            Message req = Message.request(Command.ADMIN_BOOK_SEAT)
                    .put("seatId", seat.getId()).put("date", date).put("slot", slot.getIndex())
                    .put("walkIn", guest).put("checkInNow", checkIn.isSelected());
            if (guest) {
                req.put("guestName", name.getText().trim())
                        .put("guestStudentId", studentId.getText().trim())
                        .put("guestPhone", phone.getText().trim())
                        .put("guestNote", note.getText().trim());
            }
            return req;
        }
    }

    private static String validate(String name, String studentId, String phone, String note) {
        if (name.trim().length() < 2) {
            return "Vui lòng nhập họ tên sinh viên.";
        }
        if (!studentId.trim().isEmpty() && !studentId.trim().matches("\\d{6,12}")) {
            return "Mã số sinh viên phải gồm 6-12 chữ số (hoặc để trống).";
        }
        if (!phone.trim().isEmpty() && !phone.trim().matches("\\+?[0-9 .]{8,15}")) {
            return "Số điện thoại không hợp lệ (hoặc để trống).";
        }
        if (note.trim().length() > 200) {
            return "Ghi chú tối đa 200 ký tự.";
        }
        return null;
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, c);
    }
}
