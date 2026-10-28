package library.client.ui;

import library.common.Message;
import library.common.Protocol;
import library.common.Reputation;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hộp khai báo người đi cùng khi đặt cả bàn nhiều chỗ - dùng cả lúc đặt bàn lẫn lúc sửa danh sách.
 * Sinh viên nhập mã số sinh viên hoặc tên đăng nhập, mỗi người một dòng.
 */
final class CompanionDialog {

    private CompanionDialog() {
    }

    /**
     * Hỏi danh sách rồi gửi yêu cầu do {@code makeRequest} tạo (nhận chuỗi "a,b,c"). Nếu server báo danh sách sai
     * (cờ {@code companionError}) thì mở lại hộp kèm lỗi và chữ đã nhập để sửa; lỗi khác thì hiện thông báo.
     *
     * @param onSuccess chạy khi server chấp nhận
     * @param onFailure chạy khi bị từ chối / mất kết nối (VD làm mới sơ đồ); có thể null
     */
    static void askAndSend(Component parent, ClientContext ctx, String title, String headerHtml, int capacity,
                           String error, String previous, Function<String, Message> makeRequest,
                           Consumer<Message> onSuccess, Runnable onFailure) {
        String text = previous;
        String err = error;
        LinkedHashSet<String> ids;
        int need = Protocol.minPeopleForTable(capacity) - 1;
        while (true) {
            text = show(parent, title, headerHtml, capacity, err, text);
            if (text == null) {
                return; // bấm Hủy
            }
            ids = parse(text);
            if (ids.size() < need) {
                err = "Cần ít nhất " + need + " người đi cùng (bạn mới nhập " + ids.size() + ").";
            } else if (ids.size() > capacity - 1) {
                err = "Bàn chỉ có " + capacity + " chỗ - nhập tối đa " + (capacity - 1) + " người đi cùng.";
            } else {
                break;
            }
        }
        String typed = text;
        ctx.conn().send(makeRequest.apply(String.join(",", ids))).whenComplete((resp, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                UiKit.error(parent, "Không gửi được yêu cầu tới máy chủ.\n" + UiKit.rootMessage(ex));
                if (onFailure != null) {
                    onFailure.run();
                }
            } else if (!resp.isSuccess() && resp.getBool("companionError")) { // sai danh sách → cho sửa lại
                askAndSend(parent, ctx, title, headerHtml, capacity, resp.getText(), typed, makeRequest, onSuccess,
                        onFailure);
            } else if (!resp.isSuccess()) {
                UiKit.error(parent, resp.getText());
                if (onFailure != null) {
                    onFailure.run();
                }
            } else {
                onSuccess.accept(resp);
            }
        }));
    }

    /** Hiện hộp nhập; trả về chữ đã nhập, hoặc null nếu bấm Hủy. */
    private static String show(Component parent, String title, String headerHtml, int capacity, String error,
                               String previous) {
        int minPeople = Protocol.minPeopleForTable(capacity);
        int need = minPeople - 1;
        JTextArea input = new JTextArea(previous == null ? "" : previous, 4, 30);
        input.setLineWrap(true);
        StringBuilder html = new StringBuilder("<html><div style='width:380px'>").append(headerHtml)
                .append("<br><br>Bàn có <b>").append(capacity).append(" chỗ</b>: đặt cả bàn cần ít nhất <b>")
                .append(minPeople).append(" người</b> (gồm bạn).<br>Hãy nhập <b>").append(need)
                .append(need < capacity - 1 ? "–" + (capacity - 1) : "")
                .append(" người đi cùng</b>: mã số sinh viên hoặc tên đăng nhập, mỗi người một dòng.");
        if (error != null) {
            html.append("<br><br><font color='#C93C3C'><b>").append(UiKit.html(error)).append("</b></font>");
        }
        html.append("</div></html>");
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(html.toString()), BorderLayout.NORTH);
        panel.add(new JScrollPane(input), BorderLayout.CENTER);
        panel.add(UiKit.label("<html><div style='width:380px'>Người đi cùng cần có tài khoản thư viện và phải bấm "
                + "<b>Đồng ý</b> trong ứng dụng thì bàn mới đủ người để check-in - sau "
                + Protocol.COMPANION_CONFIRM_MINUTES + " phút chưa đủ người đồng ý thì lượt đặt <b>tự hủy</b>. "
                + "Nếu cả nhóm không đến: bạn bị trừ "
                + Reputation.noShowPenalty(capacity, true) + " điểm, mỗi người đã đồng ý bị trừ "
                + Reputation.COMPANION_NO_SHOW_PENALTY + " điểm uy tín. Đi một mình thì hãy đặt chỗ đơn.</div></html>",
                Font.PLAIN, 11f, UiKit.MUTED), BorderLayout.SOUTH);
        SwingUtilities.invokeLater(input::requestFocusInWindow);
        int r = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        return r == JOptionPane.OK_OPTION ? input.getText() : null;
    }

    private static LinkedHashSet<String> parse(String text) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String t : text.split("[,;\\s]+")) {
            if (!t.trim().isEmpty()) {
                ids.add(t.trim().toLowerCase());
            }
        }
        return ids;
    }
}
