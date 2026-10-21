package library.client.ui;

import library.client.net.ServerDiscovery;
import library.client.net.ServerDiscovery.ServerInfo;
import library.common.Protocol;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/** Quét mạng LAN để tìm máy chủ thư viện (UDP broadcast). */
public class DiscoveryDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final DefaultListModel<ServerInfo> model = new DefaultListModel<>();
    private final JList<ServerInfo> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");
    private final JButton scan = new JButton("Quét lại");
    private final JButton choose = new JButton("Chọn máy chủ này");
    private ServerInfo selected;

    public DiscoveryDialog(JFrame owner) {
        super(owner, "Tìm máy chủ trong mạng LAN", true);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(30);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    chooseSelected();
                }
            }
        });
        list.addListSelectionListener(e -> choose.setEnabled(list.getSelectedValue() != null));
        choose.setEnabled(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(UiKit.padding(10, 12, 6, 12));
        top.add(status, BorderLayout.CENTER);

        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(javax.swing.BorderFactory.createCompoundBorder(UiKit.padding(0, 12, 0, 12), sp.getBorder()));

        scan.addActionListener(e -> startScan());
        choose.addActionListener(e -> chooseSelected());
        JButton close = new JButton("Đóng");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(scan);
        buttons.add(choose);
        buttons.add(close);

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(sp, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        setSize(new Dimension(520, 320));
        setLocationRelativeTo(owner);
        startScan();
    }

    private void startScan() {
        scan.setEnabled(false);
        model.clear();
        status.setText("Đang gửi gói tìm kiếm (UDP broadcast cổng " + Protocol.DISCOVERY_PORT + ")...");
        new SwingWorker<List<ServerInfo>, Void>() {
            @Override
            protected List<ServerInfo> doInBackground() {
                return ServerDiscovery.discover(Protocol.DISCOVERY_PORT, 1500);
            }

            @Override
            protected void done() {
                scan.setEnabled(true);
                try {
                    List<ServerInfo> found = get();
                    for (ServerInfo s : found) {
                        model.addElement(s);
                    }
                    if (found.isEmpty()) {
                        status.setText("<html>Không tìm thấy máy chủ nào. Kiểm tra server đã chạy, cùng mạng LAN,"
                                + " và tường lửa cho phép UDP " + Protocol.DISCOVERY_PORT + ".</html>");
                    } else {
                        status.setText("Tìm thấy " + found.size() + " máy chủ. Nhấp đúp để chọn.");
                        list.setSelectedIndex(0);
                    }
                } catch (Exception e) {
                    status.setText("Lỗi khi tìm kiếm: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void chooseSelected() {
        selected = list.getSelectedValue();
        if (selected != null) {
            dispose();
        }
    }

    public ServerInfo getSelected() {
        return selected;
    }
}
