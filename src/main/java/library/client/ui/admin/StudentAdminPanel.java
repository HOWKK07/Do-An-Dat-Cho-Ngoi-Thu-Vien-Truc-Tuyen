package library.client.ui.admin;

import library.client.ui.ClientContext;
import library.client.ui.ListTableModel;
import library.client.ui.Toast;
import library.client.ui.UiKit;
import library.common.Command;
import library.common.Fmt;
import library.common.Message;
import library.common.Reputation;
import library.common.ReputationEntry;
import library.common.ReputationInfo;
import library.common.StudentInfo;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** Quản trị sinh viên & điểm uy tín: xem điểm, số lần không đến; cộng / trừ / khôi phục điểm; xem lịch sử. */
public class StudentAdminPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final ListTableModel<StudentInfo> model = new ListTableModel<StudentInfo>()
            .col("Tài khoản", StudentInfo::getUsername)
            .col("Họ tên", StudentInfo::getFullName)
            .col("MSSV", StudentInfo::getStudentId)
            .col("Điểm uy tín", Integer.class, StudentInfo::getScore)
            .col("Mức", Reputation.Level.class, StudentInfo::getLevel)
            .col("Khóa đến hết ngày", s -> s.getLockedUntil() != null && s.getLevel() == Reputation.Level.LOCKED
                    ? Fmt.date(s.getLockedUntil().minusDays(1)) : "")
            .col("Số lần không đến", Integer.class, StudentInfo::getNoShows)
            .col("Tổng lượt tự đặt", Integer.class, StudentInfo::getTotalBookings);
    private final JTable table = UiKit.table(model);
    private final JTextField search = new JTextField(16);
    private final JLabel count = new JLabel();

    public StudentAdminPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(10, 10, 10, 10));

        table.setDefaultRenderer(Reputation.Level.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel && v instanceof Reputation.Level) {
                    setForeground(UiKit.levelColor((Reputation.Level) v));
                }
                return this;
            }
        });
        table.getColumnModel().getColumn(1).setPreferredWidth(170);

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
        JButton adjust = new JButton("Cộng / trừ điểm...");
        adjust.addActionListener(e -> adjustSelected(false));
        JButton reset = new JButton("Khôi phục " + Reputation.START + " điểm");
        reset.setToolTipText("Đưa sinh viên về mức khởi đầu " + Reputation.START + " điểm và mở khóa đặt chỗ");
        reset.addActionListener(e -> adjustSelected(true));
        JButton history = new JButton("Xem lịch sử");
        history.addActionListener(e -> showHistory());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(new JLabel("Tìm:"));
        bar.add(search);
        bar.add(refresh);
        bar.add(adjust);
        bar.add(reset);
        bar.add(history);
        bar.add(count);

        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(UiKit.label("Sắp xếp theo điểm từ thấp đến cao. Không đến: −" + Reputation.NO_SHOW_PENALTY
                + ", hủy sát giờ: −" + Reputation.LATE_CANCEL_PENALTY + ", check-in đúng hẹn: +"
                + Reputation.CHECK_IN_REWARD + ". Dưới " + Reputation.LOCK_BELOW + " điểm bị tạm khóa đặt chỗ "
                + Reputation.LOCK_DAYS + " ngày.", Font.PLAIN, 12f, UiKit.MUTED), BorderLayout.SOUTH);
    }

    public void reload() {
        UiKit.callQuiet(ctx.conn(), Message.request(Command.ADMIN_GET_STUDENTS), r -> {
            StudentInfo sel = UiKit.selected(table, model);
            java.util.List<StudentInfo> list = r.getList("students");
            model.setRows(list);
            if (sel != null) {
                UiKit.reselect(table, model, s -> s.getUsername().equals(sel.getUsername()));
            }
            long locked = list.stream().filter(s -> s.getLevel() == Reputation.Level.LOCKED).count();
            count.setText("  " + list.size() + " sinh viên, " + locked + " đang bị khóa");
        }, null);
    }

    private void applySearch() {
        String text = search.getText().trim();
        @SuppressWarnings("unchecked")
        TableRowSorter<ListTableModel<StudentInfo>> sorter = (TableRowSorter<ListTableModel<StudentInfo>>) table.getRowSorter();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?iu)" + Pattern.quote(text), 0, 1, 2));
    }

    private StudentInfo requireSelected() {
        StudentInfo s = UiKit.selected(table, model);
        if (s == null) {
            UiKit.info(this, "Hãy chọn một sinh viên trong bảng.");
        }
        return s;
    }

    private void adjustSelected(boolean resetToStart) {
        StudentInfo s = requireSelected();
        if (s == null) {
            return;
        }
        JSpinner delta = new JSpinner(new SpinnerNumberModel(-10, -100, 100, 5));
        JTextField reason = new JTextField(resetToStart ? "Đã trao đổi, cho phép đặt chỗ lại" : "", 28);
        boolean ok = resetToStart
                ? UiKit.form(this, "Khôi phục " + Reputation.START + " điểm cho " + s.getFullName(), "Điểm hiện tại",
                new JLabel(s.getScore() + " (" + s.getLevel().getLabel() + ")"), "Lý do *", reason)
                : UiKit.form(this, "Cộng / trừ điểm uy tín - " + s.getFullName(), "Điểm hiện tại",
                new JLabel(s.getScore() + " (" + s.getLevel().getLabel() + ")"),
                "Thay đổi (âm = trừ)", delta, "Lý do *", reason);
        if (!ok) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_ADJUST_REPUTATION)
                .put("username", s.getUsername()).put("delta", (Integer) delta.getValue())
                .put("reset", resetToStart).put("reason", reason.getText()), r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            reload();
        });
    }

    private void showHistory() {
        StudentInfo s = requireSelected();
        if (s == null) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_GET_REPUTATION).put("username", s.getUsername()),
                r -> {
                    ReputationInfo info = r.get("reputation");
                    ListTableModel<ReputationEntry> m = new ListTableModel<ReputationEntry>()
                            .col("Thời gian", LocalDateTime.class, ReputationEntry::getCreatedAt)
                            .col("Thay đổi", ReputationEntry::getDeltaLabel)
                            .col("Điểm sau", Integer.class, ReputationEntry::getScoreAfter)
                            .col("Lý do", ReputationEntry::getReason);
                    m.setRows(info.getHistory());
                    JTable t = UiKit.table(m);
                    t.getColumnModel().getColumn(3).setPreferredWidth(480);
                    Window owner = SwingUtilities.getWindowAncestor(this);
                    JDialog d = new JDialog(owner, "Lịch sử điểm uy tín - " + info.getFullName() + " ("
                            + info.getScore() + " điểm, " + info.getLevel().getLabel() + ")");
                    d.getContentPane().add(new JScrollPane(t));
                    d.setSize(820, 420);
                    d.setLocationRelativeTo(owner);
                    d.setVisible(true);
                });
    }
}
