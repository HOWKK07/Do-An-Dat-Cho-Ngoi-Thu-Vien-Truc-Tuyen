package library.client.ui;

import library.common.Announcement;
import library.common.Command;
import library.common.Fmt;
import library.common.Message;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

/** Bảng thông báo của thư viện. Quản trị viên có thêm nút đăng / xóa. */
public class AnnouncementsPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final ClientContext ctx;
    private final DefaultListModel<Announcement> model = new DefaultListModel<>();
    private final JList<Announcement> list = new JList<>(model);
    private final JLabel title = UiKit.label(" ", Font.BOLD, 17f, UiKit.PRIMARY);
    private final JLabel meta = UiKit.label(" ", Font.PLAIN, 12f, UiKit.MUTED);
    private final JTextArea content = new JTextArea();

    public AnnouncementsPanel(ClientContext ctx) {
        super(new BorderLayout(0, 8));
        this.ctx = ctx;
        setBorder(UiKit.padding(12, 12, 12, 12));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, index, sel, focus);
                Announcement a = (Announcement) value;
                setText("<html><div style='width:220px'><b>" + UiKit.html(a.getTitle()) + "</b><br><font size='-1'>"
                        + Fmt.dateTime(a.getCreatedAt()) + " • " + UiKit.html(a.getAuthor()) + "</font></div></html>");
                setBorder(UiKit.padding(8, 10, 8, 10));
                return this;
            }
        });
        list.addListSelectionListener(e -> showSelected());

        content.setEditable(false);
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        content.setFont(UiKit.font(Font.PLAIN, 14f));
        content.setBorder(UiKit.padding(8, 2, 8, 2));

        JPanel detail = new JPanel(new BorderLayout(0, 6));
        detail.setBorder(UiKit.padding(4, 14, 4, 4));
        JPanel head = new JPanel(new BorderLayout());
        head.add(title, BorderLayout.NORTH);
        head.add(meta, BorderLayout.SOUTH);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UiKit.BORDER),
                UiKit.padding(0, 0, 8, 0)));
        detail.add(head, BorderLayout.NORTH);
        JScrollPane cs = new JScrollPane(content);
        cs.setBorder(BorderFactory.createEmptyBorder());
        detail.add(cs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), detail);
        split.setDividerLocation(330);
        add(split, BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = new JButton("Làm mới");
        refresh.addActionListener(e -> reload());
        toolbar.add(refresh);
        if (ctx.isAdmin()) {
            JButton post = new JButton("Đăng thông báo mới");
            post.setFont(UiKit.font(Font.BOLD, 13f));
            post.addActionListener(e -> postNew());
            JButton delete = new JButton("Xóa thông báo đã chọn");
            delete.addActionListener(e -> deleteSelected());
            toolbar.add(post);
            toolbar.add(delete);
        }
        add(toolbar, BorderLayout.NORTH);
    }

    public void reload() {
        UiKit.callQuiet(ctx.conn(), Message.request(Command.GET_ANNOUNCEMENTS), r -> {
            Announcement sel = list.getSelectedValue();
            String previousNewest = model.isEmpty() ? null : model.get(0).getId();
            List<Announcement> items = r.getList("announcements");
            model.clear();
            int index = 0;
            for (int i = 0; i < items.size(); i++) {
                model.addElement(items.get(i));
                if (sel != null && items.get(i).getId().equals(sel.getId())) {
                    index = i;
                }
            }
            if (!items.isEmpty() && previousNewest != null && !items.get(0).getId().equals(previousNewest)) {
                index = 0; // có thông báo mới → hiển thị ngay thông báo mới nhất
            }
            if (!items.isEmpty()) {
                list.setSelectedIndex(index);
            } else {
                showSelected();
            }
        }, null);
    }

    private void showSelected() {
        Announcement a = list.getSelectedValue();
        if (a == null) {
            title.setText("Chưa có thông báo");
            meta.setText(" ");
            content.setText("");
            return;
        }
        title.setText("<html>" + UiKit.html(a.getTitle()) + "</html>");
        meta.setText("Đăng lúc " + Fmt.dateTime(a.getCreatedAt()) + " bởi " + a.getAuthor());
        content.setText(a.getContent());
        content.setCaretPosition(0);
    }

    private void postNew() {
        JTextField t = new JTextField(40);
        JTextArea c = new JTextArea(10, 40);
        c.setLineWrap(true);
        c.setWrapStyleWord(true);
        if (!UiKit.form(this, "Đăng thông báo mới", "Tiêu đề", t, "Nội dung", new JScrollPane(c))) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_POST_ANNOUNCEMENT)
                .put("title", t.getText()).put("content", c.getText()), r -> {
            Toast.show(this, r.getText(), UiKit.SUCCESS);
            reload();
        });
    }

    private void deleteSelected() {
        Announcement a = list.getSelectedValue();
        if (a == null || !UiKit.confirm(this, "Xóa thông báo \"" + a.getTitle() + "\"?")) {
            return;
        }
        UiKit.call(this, ctx.conn(), Message.request(Command.ADMIN_DELETE_ANNOUNCEMENT).put("id", a.getId()),
                r -> reload());
    }
}
