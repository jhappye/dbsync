package com.zenplanner.sql;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

public class DbComparator {

    private static final String filterCol = "partitionId";
    private static final int maxKeys = 2000; // jtds driver limit

    public enum ChangeType {
        INSERT, UPDATE, DELETE, NONE
    }

    public DbComparator() {

    }

    public static void Syncronize(Connection scon, Connection dcon, String filterValue) {
        try {
            Map<String, Table> srcTables = filterTables(getTables(scon));
            Map<String, Table> dstTables = getTables(dcon);
            try {
                setConstraints(dcon, dstTables.values(), false);
                for (Table srcTable : srcTables.values()) {
                    if (!dstTables.containsKey(srcTable.getName())) {
                        continue;
                    }
                    Table dstTable = dstTables.get(srcTable.getName());
                    System.out.println("Comparing table: " + srcTable.getName());
                    syncTables(scon, dcon, srcTable, dstTable, filterValue);
                }
            } catch (Exception ex) {
                throw ex;
            } finally {
                setConstraints(dcon, dstTables.values(), true);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error comparing databases!", ex);
        }
    }

    private static void setConstraints(Connection con, Collection<Table> tables, boolean enabled) {
        for(Table table : tables) {
            table.setConstraints(con, false);
        }
    }

    /**
     * Retrieves a map of Tables from the database schema
     *
     * @param con The connection to use to query the DB for its schema
     * @return A map of Tables from the database schema
     * @throws Exception
     */
    private static Map<String, Table> getTables(Connection con) throws Exception {
        Map<String, Table> tables = new HashMap<>();
        try (Statement stmt = con.createStatement()) {
            String sql = Resources.toString(Resources.getResource("GetTables.sql"), Charsets.UTF_8);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    if (!tables.containsKey(tableName)) {
                        tables.put(tableName, new Table(tableName));
                    }
                    Table table = tables.get(tableName);

                    Column col = new Column();
                    String colName = rs.getString("column_name");
                    col.setColumnName(colName);
                    col.setDataType(rs.getString("data_type"));
                    col.setPrimaryKey(rs.getBoolean("primary_key"));
                    table.put(colName, col);
                }
            }
        }
        return tables;
    }

    /**
     * Compares two tables and syncronizes the results
     *
     * @param scon        The source connection
     * @param dcon        The destination connection
     * @param srcTable    The source table
     * @param dstTable    The destination table
     * @param filterValue A partitionId
     * @throws Exception
     */
    private static void syncTables(Connection scon, Connection dcon, Table srcTable, Table dstTable,
                                   String filterValue) throws Exception {
        Table lcd = findLcd(srcTable, dstTable);
        String sql = lcd.writeHashedQuery(filterCol);
        //int i = 0; // TODO: Threading and progress indicator
        try (PreparedStatement stmt = scon.prepareStatement(sql); PreparedStatement dtmt = dcon.prepareStatement(sql)) {
            if(lcd.hasColumn(filterCol)) {
                stmt.setObject(1, filterValue);
                dtmt.setObject(1, filterValue);
            }
            List<String> pairs = new ArrayList<>();  // Debugging
            try (ResultSet srs = stmt.executeQuery(); ResultSet drs = dtmt.executeQuery()) {
                srs.next();
                drs.next();
                Map<ChangeType, Set<Key>> changes = new HashMap<>();
                changes.put(ChangeType.INSERT, new HashSet<>());
                changes.put(ChangeType.UPDATE, new HashSet<>());
                changes.put(ChangeType.DELETE, new HashSet<>());
                while (srs.getRow() > 0 || drs.getRow() > 0) {
                    //System.out.println("Syncing row " + (++i));
                    ChangeType change = detectChange(lcd, srs, drs);

                    // Debugging
                    Key spk = lcd.getPk(srs); // Debugging
                    Key dpk = lcd.getPk(drs); // Debugging
                    pairs.add("" + spk + "-" + dpk + " " + change); // Debugging

                    Key key = getPk(lcd, srs, drs);
                    Set<Key> changeset = changes.get(change);
                    if (changeset == null) {
                        continue;
                    }
                    changeset.add(key);
                    advance(srcTable, dstTable, srs, drs);

                    // Debugging
                    //insertRows(scon, dcon, lcd, changes.get(ChangeType.INSERT)); // Debugging
                    //changes.get(ChangeType.INSERT).clear(); // Debugging
                }
                insertRows(scon, dcon, lcd, changes.get(ChangeType.INSERT));
            }
        }
    }

    private static String keyListToString(List<Key> keys) {
        StringBuilder sb = new StringBuilder();
        for(Key key : keys) {
            sb.append(key.toString());
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void insertRows(Connection scon, Connection dcon, Table table, Set<Key> keys) throws Exception {
        if (keys.size() <= 0) {
            return;
        }

        table.setIdentityInsert(dcon, true);
        List<Column> pk = table.getPk();
        int rowLimit = (int) Math.floor(maxKeys / pk.size());
        for (int rowIndex = 0; rowIndex < keys.size(); ) {
            int count = Math.min(keys.size() - rowIndex, rowLimit);
            count = 1; // Debugging
            System.out.println("Inserting " + count + " rows into " + table.getName());
            try (PreparedStatement selectStmt = createSelectQuery(scon, table, keys, count)) {
                try (ResultSet rs = selectStmt.executeQuery()) {
                    String sql = table.writeInsertQuery();
                    try (PreparedStatement insertStmt = dcon.prepareStatement(sql)) {
                        while (rs.next()) {
                            insertRow(insertStmt, table, rs);
                        }
                        try {
                            insertStmt.executeBatch();
                        } catch (Exception ex) {
                            throw new RuntimeException("Error inserting rows!", ex);
                        }
                    }
                }
            }
            rowIndex += count;
        }
    }

    /**
     * Adds the values from a given row to the PreparedStatement
     *
     * @param stmt  The PreparedStatement to help prepare
     * @param table The table definition
     * @param rs    The ResultSet to pull values from
     * @throws Exception
     */
    private static void insertRow(PreparedStatement stmt, Table table, ResultSet rs) throws Exception {
        stmt.clearParameters();
        int i = 0;
        for (Column col : table.values()) {
            String colName = col.getColumnName();
            Object val = rs.getObject(colName);
            stmt.setObject(++i, val);
        }
        stmt.addBatch();
    }

    /**
     * Takes two RecordSets, and advances one cursor, or the other, or both to keep the PKs in sync
     *
     * @param srcTable The source table
     * @param dstTable The destination table
     * @param srs      The source RecordSet
     * @param drs      The destination RecordSet
     * @throws Exception
     */
    private static void advance(Table srcTable, Table dstTable, ResultSet srs, ResultSet drs) throws Exception {
        Key spk = srcTable.getPk(srs);
        Key dpk = dstTable.getPk(drs);
        int val = Key.compare(spk, dpk);
        if (val < 0) {
            srs.next();
            return;
        }
        if (val > 0) {
            drs.next();
            return;
        }
        srs.next();
        drs.next();
    }

    /**
     * Gets the primary key from whichever row exists
     *
     * @param table The table definition
     * @param srs   The source RecordSet
     * @param drs   The destination RecordSet
     * @return The primary key of the row
     * @throws Exception
     */
    private static Key getPk(Table table, ResultSet srs, ResultSet drs) throws Exception {
        ChangeType change = detectChange(table, srs, drs);
        if (change == ChangeType.DELETE) {
            return table.getPk(drs);
        }
        return table.getPk(srs);
    }

    /**
     * Creates a virtual table that contains the intersection of the columns of two other real tables
     *
     * @param srcTable The source table
     * @param dstTable The destination table
     * @return a virtual table that contains the intersection of the columns of two other real tables
     */
    private static Table findLcd(Table srcTable, Table dstTable) {
        Table table = new Table(srcTable.getName());
        Set<String> colNames = new HashSet<>();
        colNames.addAll(srcTable.keySet());
        colNames.addAll(dstTable.keySet());
        for (String colName : colNames) {
            if (!srcTable.containsKey(colName) || !dstTable.containsKey(colName)) {
                continue;
            }
            table.put(colName, srcTable.get(colName));
        }
        return table;
    }

    /**
     * Creates a PreparedStatement that returns all of the rows for the given set of keys.
     * Don't forget to close the statement!
     *
     * @param con   The connection to use to query the DB for its schema
     * @param table The table definition
     * @param keys  The keys of the rows for which to query
     * @return A PreparedStatement that returns all the rows for the given set of keys.
     * @throws Exception
     */
    // TODO: Break this monster out into separate methods for SQL and values
    private static PreparedStatement createSelectQuery(Connection con, Table table, Set<Key> keys, int count)
            throws Exception {
        List<Object> parms = new ArrayList<>();
        List<Column> pk = table.getPk();
        StringBuilder sb = new StringBuilder();
        int rowIndex = 0;
        for(Key key : new HashSet<>(keys)) {
            keys.remove(key); // Remove as we go
            if (sb.length() > 0) {
                sb.append("\tor ");
            }
            sb.append("(");
            for (int pkIdx = 0; pkIdx < pk.size(); pkIdx++) {
                if (pkIdx > 0) {
                    sb.append(" and ");
                }
                Column col = pk.get(pkIdx);
                sb.append("[");
                sb.append(col.getColumnName());
                sb.append("]=?");

                // Grab the value of the parameter
                Object val = key.get(pkIdx);
                parms.add(val);
            }
            sb.append(")\n");
            if(rowIndex++ >= count) {
                break;
            }
        }
        String sql = String.format("select\n\t*\nfrom [%s]\nwhere %s", table.getName(), sb.toString());
        PreparedStatement stmt = con.prepareStatement(sql);
        for (int i = 0; i < parms.size(); i++) {
            stmt.setObject(i + 1, parms.get(i));
        }
        return stmt;
    }

    /**
     * Filters a map of database tables and returns only the ones that are sync-able
     *
     * @param in The map to filter
     * @return The filtered map
     */
    private static Map<String, Table> filterTables(Map<String, Table> in) {
        Map<String, Table> out = new HashMap<>();
        for (Map.Entry<String, Table> entry : in.entrySet()) {
            boolean hasPk = false;
            String name = entry.getKey();
            Table table = entry.getValue();
            for (Column col : table.values()) {
                if (col.isPrimaryKey()) {
                    hasPk = true;
                }
            }
            if (hasPk) {
                out.put(name, table);
            }
        }
        return out;
    }

    /**
     * Basically this is the join logic. It compares the two rows presently under the cursors, and returns an action
     * that needs to be taken based on whether the row is in left but not right, right but not left, or in both but
     * changes are present. As usual for join code, this method assumes that the ResultSets are ordered, and the
     * Key.compare() method exhibits the same ordering as the database engine.
     *
     * @param table The table definition
     * @param srs The source RecordSet
     * @param drs The destination RecordSet
     * @return A ChangeType indicating what action should be taken to sync the two databases
     * @throws Exception
     */
    public static ChangeType detectChange(Table table, ResultSet srs, ResultSet drs) throws Exception {
        // Verify we're on the same row
        Key spk = table.getPk(srs);
        Key dpk = table.getPk(drs);
        int eq = Key.compare(spk, dpk);
        if(eq < 0) {
            // Source cursor is above dest cursor - destination row isn't present in source
            return ChangeType.DELETE;
        }
        if(eq > 0) {
            // Dest cursor is above source cursor - source row isn't in destination
            return ChangeType.INSERT;
        }

        // Keys match, check hashes
        byte[] shash = getHash(srs);
        byte[] dhash = getHash(drs);
        if (shash == null && dhash == null) {
            throw new RuntimeException("Both rows are null!");
        }
        if (shash == null) {
            return ChangeType.DELETE;
        }
        if (dhash == null) {
            return ChangeType.INSERT;
        }
        if (shash.equals(dhash)) {
            return ChangeType.NONE;
        }
        return ChangeType.UPDATE;
    }

    /**
     * Get the Hash from a ResultSet, or returns null if the ResultSet is exhausted
     *
     * @param rs The ResultSet
     * @return The Hash, or null
     */
    private static byte[] getHash(ResultSet rs) throws Exception {
        if (rs == null || rs.isBeforeFirst() || rs.isAfterLast() || rs.getRow() == 0) {
            return null;
        }
        return rs.getBytes("Hash");
    }

}
