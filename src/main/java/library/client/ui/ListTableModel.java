package library.client.ui;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Model bảng gắn với danh sách đối tượng, khai báo cột theo kiểu builder. */
public class ListTableModel<T> extends AbstractTableModel {
    private static final long serialVersionUID = 1L;

    private final List<String> names = new ArrayList<>();
    private final List<Class<?>> types = new ArrayList<>();
    private final List<Function<T, ?>> getters = new ArrayList<>();
    private List<T> rows = new ArrayList<>();

    public ListTableModel<T> col(String name, Function<T, ?> getter) {
        return col(name, Object.class, getter);
    }

    public ListTableModel<T> col(String name, Class<?> type, Function<T, ?> getter) {
        names.add(name);
        types.add(type);
        getters.add(getter);
        return this;
    }

    public void setRows(List<T> newRows) {
        rows = new ArrayList<>(newRows);
        fireTableDataChanged();
    }

    public T getRow(int modelIndex) {
        return rows.get(modelIndex);
    }

    public List<T> getRows() {
        return rows;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return names.size();
    }

    @Override
    public String getColumnName(int column) {
        return names.get(column);
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return types.get(column);
    }

    @Override
    public Object getValueAt(int row, int column) {
        return getters.get(column).apply(rows.get(row));
    }
}
