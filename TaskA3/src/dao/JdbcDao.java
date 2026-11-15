package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The steps every hand-written DB class repeated, written ONCE:
 *   borrow connection -> prepare SQL -> bind parameters -> execute -> map rows -> close / give back (always).
 * A concrete DAO only supplies what differs: table name, column mapping, row mapper and key values.
 * The five SQL statements are generated once from the mapping, so a column name exists in one place.
 */
public abstract class JdbcDao<T, K> implements Dao<T, K> {
    @FunctionalInterface public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private final ConnectionSource source;
    private final List<Column<T>> columns;
    private final RowMapper<T> mapper;
    private final Function<K, Object[]> keyValues;
    final String insertSql, selectAllSql, selectByKeySql, updateSql, deleteSql;

    protected JdbcDao(ConnectionSource source, String table, List<Column<T>> columns, RowMapper<T> mapper, Function<K, Object[]> keyValues) {
        this.source = source; this.columns = columns; this.mapper = mapper; this.keyValues = keyValues;
        String all = columns.stream().map(Column::name).collect(Collectors.joining(", "));
        String keyWhere = columns.stream().filter(Column::key).map(c -> c.name() + " = ?").collect(Collectors.joining(" AND "));
        String set = columns.stream().filter(c -> !c.key()).map(c -> c.name() + " = ?").collect(Collectors.joining(", "));
        String order = columns.stream().filter(Column::key).map(Column::name).collect(Collectors.joining(", "));
        insertSql = "INSERT INTO " + table + " (" + all + ") VALUES (" + "?, ".repeat(columns.size() - 1) + "?)";
        selectAllSql = "SELECT " + all + " FROM " + table + " ORDER BY " + order;
        selectByKeySql = "SELECT " + all + " FROM " + table + " WHERE " + keyWhere;
        updateSql = "UPDATE " + table + " SET " + set + " WHERE " + keyWhere;
        deleteSql = "DELETE FROM " + table + " WHERE " + keyWhere;
    }

    // ------------------------------------------------------------ the common pattern, once
    protected final List<T> query(String sql, Object... params) throws SQLException {
        Connection c = source.borrow();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } finally { source.giveBack(c); }
    }

    protected final int execute(String sql, Object... params) throws SQLException {
        Connection c = source.borrow();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } finally { source.giveBack(c); }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    private Object[] values(T e, boolean keysLast) {
        List<Object> v = new ArrayList<>();
        if (!keysLast) { for (Column<T> c : columns) v.add(c.getter().apply(e)); return v.toArray(); }
        for (Column<T> c : columns) if (!c.key()) v.add(c.getter().apply(e));
        for (Column<T> c : columns) if (c.key()) v.add(c.getter().apply(e));
        return v.toArray();
    }

    // ------------------------------------------------------------ Dao<T, K>
    @Override public void insert(T e) throws SQLException { execute(insertSql, values(e, false)); }
    @Override public Optional<T> findById(K id) throws SQLException { return query(selectByKeySql, keyValues.apply(id)).stream().findFirst(); }
    @Override public List<T> findAll() throws SQLException { return query(selectAllSql); }
    @Override public boolean update(T e) throws SQLException { return execute(updateSql, values(e, true)) == 1; }
    @Override public boolean delete(K id) throws SQLException { return execute(deleteSql, keyValues.apply(id)) == 1; }

    public List<Column<T>> columns() { return columns; }
    public String sqlSummary() {
        return String.join("\n", "  " + insertSql, "  " + selectByKeySql, "  " + selectAllSql, "  " + updateSql, "  " + deleteSql);
    }
}
