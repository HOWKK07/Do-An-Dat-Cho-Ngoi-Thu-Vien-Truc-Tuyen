package library.server.db;

import library.common.Zone;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Truy vấn bảng {@code zones} (khu vực). */
public final class ZoneDao {
    private static final String COLUMNS = "id, name, floor, description";

    private ZoneDao() {
    }

    private static Zone map(ResultSet rs) throws SQLException {
        return new Zone(rs.getString("id"), rs.getString("name"), rs.getInt("floor"), rs.getString("description"));
    }

    public static List<Zone> list(Connection c) throws SQLException {
        List<Zone> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM zones ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    public static Zone find(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM zones WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public static void insert(Connection c, Zone z) throws SQLException {
        Sql.update(c, "INSERT INTO zones (id, name, floor, description) VALUES (?,?,?,?)",
                z.getId(), z.getName(), z.getFloor(), z.getDescription());
    }

    public static int update(Connection c, Zone z) throws SQLException {
        return Sql.update(c, "UPDATE zones SET name = ?, floor = ?, description = ? WHERE id = ?",
                z.getName(), z.getFloor(), z.getDescription(), z.getId());
    }

    /** Xóa khu vực - các chỗ ngồi thuộc khu bị xóa theo (FOREIGN KEY ... ON DELETE CASCADE). */
    public static int delete(Connection c, String id) throws SQLException {
        return Sql.update(c, "DELETE FROM zones WHERE id = ?", id);
    }

    public static int count(Connection c) throws SQLException {
        return Sql.count(c, "SELECT COUNT(*) FROM zones");
    }
}
