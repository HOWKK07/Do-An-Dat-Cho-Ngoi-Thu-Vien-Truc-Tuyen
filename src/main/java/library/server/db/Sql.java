package library.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;

/** Tiện ích JDBC dùng chung cho các DAO. */
public final class Sql {

    private Sql() {
    }

    static int count(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** "?, ?, ?" cho mệnh đề IN. */
    static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    static Object[] array(Collection<?> values) {
        return values.toArray(new Object[0]);
    }

    /** Lỗi vi phạm khóa UNIQUE có tên {@code keyName} không (MySQL: "Duplicate entry ... for key 'uq_...'"). */
    public static boolean isDuplicate(SQLException e, String keyName) {
        boolean integrity = e instanceof SQLIntegrityConstraintViolationException
                || e.getErrorCode() == 1062
                || (e.getSQLState() != null && e.getSQLState().startsWith("23"));
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return integrity && msg.contains(keyName.toLowerCase(Locale.ROOT));
    }
}
