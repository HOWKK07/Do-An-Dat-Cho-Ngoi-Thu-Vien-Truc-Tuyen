package library.client.ui;

import library.common.Command;
import library.common.Fmt;
import library.common.Message;
import library.common.Reputation;
import library.common.ReputationEntry;
import library.common.ReputationInfo;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDateTime;
import java.util.function.Consumer;

/** Tab "Điểm uy tín" của sinh viên: điểm hiện tại, quyền đặt chỗ, quy tắc và lịch sử cộng / trừ điểm. */
public class ReputationPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final Consumer<ReputationInfo> onLoaded;
    private final JLabel scoreLabel = UiKit.label("--", Font.BOLD, 44f, UiKit.PRIMARY);
    private final JLabel levelLabel = UiKit.label(" ", Font.BOLD, 18f, UiKit.SUCCESS);
    private final JLabel rightsLabel = UiKit.label(" ", Font.PLAIN, 13f, null);
    private final JProgressBar bar = new JProgressBar(0, Reputation.MAX);
    private final ListTableModel<ReputationEntry> model = new ListTableModel<ReputationEntry>()
            .col("Thời gian", LocalDateTime.class, ReputationEntry::getCreatedAt)
            .col("Thay đổi", ReputationEntry::getDeltaLabel)
            .col("Điểm sau", Integer.class, ReputationEntry::getScoreAfter)
            .col("Lý do", ReputationEntry::getReason);
    private final JTable table = UiKit.table(model);

    public ReputationPanel(ClientContext ctx, Consumer<ReputationInfo> onLoaded) {
        super(new BorderLayout(12, 12));
        this.ctx = ctx;
        this.onLoaded = onLoaded;
        setBorder(UiKit.padding(12, 12, 12, 12));

        bar.setPreferredSize(new Dimension(260, 14));
        bar.setStringPainted(false);

        JPanel scoreCard = new JPanel();
        scoreCard.setLayout(new BoxLayout(scoreCard, BoxLayout.Y_AXIS));
        scoreCard.setBackground(UiKit.CARD);
        scoreCard.setBorder(UiKit.cardBorder());
        scoreCard.add(UiKit.label("Điểm uy tín của bạn", Font.PLAIN, 13f, UiKit.MUTED));
        scoreCard.add(scoreLabel);
        scoreCard.add(levelLabel);
        scoreCard.add(Box.createVerticalStrut(8));
        JPanel barWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        barWrap.setOpaque(false);
        barWrap.setAlignmentX(LEFT_ALIGNMENT);
        barWrap.add(bar);
        scoreCard.add(barWrap);
        scoreCard.add(Box.createVerticalStrut(10));
        scoreCard.add(rightsLabel);
        for (Component c : scoreCard.getComponents()) {
            ((javax.swing.JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
        }

        JPanel rulesCard = new JPanel(new BorderLayout());
        rulesCard.setBackground(UiKit.CARD);
        rulesCard.setBorder(UiKit.cardBorder());
        rulesCard.add(UiKit.label(Reputation.rulesHtml(), Font.PLAIN, 13f, null), BorderLayout.NORTH);

        JPanel top = new JPanel(new GridLayout(1, 2, 12, 0));
        top.add(scoreCard);
        top.add(rulesCard);

        table.getColumnModel().getColumn(0).setPreferredWidth(130);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(600);
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, focus, r, c);
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel) {
                    setForeground(String.valueOf(v).startsWith("+") ? UiKit.SUCCESS : UiKit.DANGER);
                }
                return this;
            }
        });

        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> reload());
        JPanel historyHeader = new JPanel(new BorderLayout());
        historyHeader.add(UiKit.label("Lịch sử cộng / trừ điểm", Font.BOLD, 14f, UiKit.PRIMARY), BorderLayout.WEST);
        historyHeader.add(refresh, BorderLayout.EAST);
        JPanel history = new JPanel(new BorderLayout(0, 6));
        history.add(historyHeader, BorderLayout.NORTH);
        history.add(new JScrollPane(table), BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(history, BorderLayout.CENTER);
    }

    public void reload() {
        UiKit.callQuiet(ctx.conn(), Message.request(Command.GET_MY_REPUTATION), r -> {
            ReputationInfo info = r.get("reputation");
            show(info);
            if (onLoaded != null) {
                onLoaded.accept(info);
            }
        }, null);
    }

    private void show(ReputationInfo info) {
        Color color = UiKit.levelColor(info.getLevel());
        scoreLabel.setText(info.getScore() + " / " + Reputation.MAX);
        scoreLabel.setForeground(color);
        levelLabel.setText("Mức: " + info.getLevel().getLabel());
        levelLabel.setForeground(color);
        bar.setValue(info.getScore());
        bar.setForeground(color);
        Reputation.Level lv = info.getLevel();
        if (lv == Reputation.Level.LOCKED) {
            rightsLabel.setText("<html><div style='width:320px'><font color='#C93C3C'><b>Đang bị tạm khóa đặt chỗ</b>"
                    + (info.getLockedUntil() != null ? " đến hết ngày " + Fmt.date(info.getLockedUntil().minusDays(1))
                    + ". Sau đó tài khoản tự mở lại ở mức " + Reputation.LOCK_BELOW + " điểm." : ".")
                    + "</font><br>Liên hệ thủ thư nếu cần hỗ trợ.</div></html>");
        } else {
            String hint = Reputation.nextLevelHint(lv);
            rightsLabel.setText("<html><div style='width:320px'>Bạn được đặt tối đa <b>" + lv.getMaxPerDay()
                    + " ca/ngày</b>, đặt trước tối đa <b>" + lv.getDaysAhead() + " ngày</b> (tính cả hôm nay)."
                    + (hint == null ? "<br>Đây là mức cao nhất - hãy giữ thói quen check-in đúng hẹn nhé!"
                    : "<br>" + hint) + "</div></html>");
        }
        model.setRows(info.getHistory());
        if (info.getHistory().isEmpty()) {
            table.setToolTipText("Chưa có thay đổi nào");
        }
        table.setBorder(BorderFactory.createEmptyBorder());
    }
}
