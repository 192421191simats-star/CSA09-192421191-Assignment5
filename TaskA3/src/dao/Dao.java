package dao;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Task A3.2 - generic data-access interface: T = entity type, K = primary-key type. */
public interface Dao<T, K> {
    void insert(T entity) throws SQLException;
    Optional<T> findById(K id) throws SQLException;
    List<T> findAll() throws SQLException;
    boolean update(T entity) throws SQLException;      // false if no row has that key
    boolean delete(K id) throws SQLException;          // false if no row has that key
}
