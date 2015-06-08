/*
 * Copyright 2014-2015 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kaaproject.kaa.client.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class SQLiteDBLogStorage implements LogStorage, LogStorageStatus {

    private static final Logger LOG = LoggerFactory.getLogger(SQLiteDBLogStorage.class);

    private static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";
    private static final String TABLE_NAME = "kaa_logs";
    private static final String RECORD_ID_COLUMN = "record_id";
    private static final String BUCKET_ID_COLUMN = "bucket_id";
    private static final String LOG_DATA_COLUMN = "log_data";
    private static final String BUCKET_ID_INDEX_NAME = "IX_BUCKET_ID";

    private static final String KAA_CREATE_LOG_TABLE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("  +
            RECORD_ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            BUCKET_ID_COLUMN + " INTEGER, " +
            LOG_DATA_COLUMN + " BLOB);";

    private static final String KAA_CREATE_BUCKET_ID_INDEX =
            "CREATE INDEX IF NOT EXISTS " + BUCKET_ID_INDEX_NAME + " " +
            " ON " + TABLE_NAME +  " (" + BUCKET_ID_COLUMN + ");";

    private static final String KAA_HOW_MANY_LOGS_IN_DB =
            "SELECT COUNT(*), SUM(LENGTH(" +
            LOG_DATA_COLUMN + ")) FROM " + TABLE_NAME + ";";

    private static final String KAA_RESET_BUCKET_ID_ON_START =
            "UPDATE " + TABLE_NAME + " " +
            "SET " + BUCKET_ID_COLUMN + " = NULL " +
            "WHERE " + BUCKET_ID_COLUMN + " IS NOT NULL;";

    private static final String KAA_INSERT_NEW_RECORD =
            "INSERT INTO " + TABLE_NAME + " (" + LOG_DATA_COLUMN + ") " +
            "VALUES (?);";

    private static final String KAA_SELECT_UNMARKED_RECORDS =
            "SELECT " + RECORD_ID_COLUMN + ", " + LOG_DATA_COLUMN + " " +
            "FROM " + TABLE_NAME + " " +
            "WHERE " + BUCKET_ID_COLUMN + " IS NULL " +
            "ORDER BY " + RECORD_ID_COLUMN + " ASC;";

    private static final String KAA_DELETE_BY_RECORD_ID =
            "DELETE FROM " + TABLE_NAME + " " +
            "WHERE " + RECORD_ID_COLUMN + " = ?;";

    private static final String SUBSTITUTE_SYMBOL = "?";
    private static final String KAA_UPDATE_BUCKET_ID =
            "UPDATE " + TABLE_NAME + " "  +
            "SET " + BUCKET_ID_COLUMN + " = ? " +
            "WHERE " + RECORD_ID_COLUMN + " IN (?);";

    private static final String KAA_DELETE_BY_BUCKET_ID =
            "DELETE FROM " + TABLE_NAME + " " +
            "WHERE " + BUCKET_ID_COLUMN + " = ?;";


    private static final String KAA_RESET_BY_BUCKET_ID =
            "UPDATE " + TABLE_NAME + " " +
            "SET " + BUCKET_ID_COLUMN + " = NULL " +
            "WHERE " + BUCKET_ID_COLUMN + " = ?;";

    private PreparedStatement insertStatement;
    private PreparedStatement deleteByRecordIdStatement;
    private PreparedStatement deleteByBucketIdStatement;
    private PreparedStatement setBucketIdStatement;
    private PreparedStatement resetBucketIdStatement;

    private long recordCount;
    private long consumedSize;
    private int currentBucketId;

    private Map<Integer, Long> consumedMemoryStorage = new HashMap<>();

    private final Connection connection;

    public SQLiteDBLogStorage(String dbName) {
        try {
            Class.forName("org.sqlite.JDBC");
            String dbURL = SQLITE_URL_PREFIX + dbName;
            LOG.info("Connecting to db by url: {}", dbURL);
            connection = DriverManager.getConnection(dbURL);
            LOG.debug("SQLite connection was successfully established");
            initTable();
            retrieveConsumedSizeAndVolume();
            if (recordCount > 0) {
                resetBucketIDs();
            }
        } catch (ClassNotFoundException e) {
            LOG.error("Can't find SQLite classes in classpath", e);
            throw new RuntimeException(e);
        } catch (SQLException e) {
            LOG.error("Error while initializing SQLite DB and its tables", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addLogRecord(LogRecord record) {
        synchronized (connection) {
            LOG.trace("Adding a new log record...");
            if (insertStatement == null) {
                try {
                    insertStatement = connection.prepareStatement(KAA_INSERT_NEW_RECORD);
                } catch (SQLException e) {
                    LOG.error("Can't create row insert statement: {}", KAA_INSERT_NEW_RECORD, e);
                    throw new RuntimeException(e);
                }
            }

            try {
                insertStatement.setBytes(1, record.getData());
                int affectedRows = insertStatement.executeUpdate();
                if (affectedRows == 1) {
                    consumedSize += record.getSize();
                    recordCount++;
                    LOG.trace("Added a new log record, records count: [{}], data: {}", recordCount, record.getData());
                } else {
                    LOG.warn("No log record was added");
                }
            } catch (SQLException e) {
                LOG.error("Can't add a new record", e);
            }
        }
    }

    @Override
    public LogStorageStatus getStatus() {
        return this;
    }

    @Override
    public LogBlock getRecordBlock(long blockSize) {
        synchronized (connection) {
            LOG.trace("Creating a new record block, needed size: {}", blockSize);
            Statement statement = null;
            LogBlock logBlock = null;
            List<String> unmarkedRecordIds = new LinkedList<>();
            List<LogRecord> logRecords = new LinkedList<>();
            try {
                long leftBlockSize = blockSize;
                statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(KAA_SELECT_UNMARKED_RECORDS);
                while (rs.next()) {
                    int recordId = rs.getInt(1);
                    byte[] recordData = rs.getBytes(2);

                    if (recordData != null && recordData.length > 0) {
                        if (leftBlockSize < recordData.length) {
                            break;
                        }
                        logRecords.add(new LogRecord(recordData));
                        unmarkedRecordIds.add(String.valueOf(recordId));
                        leftBlockSize -= recordData.length;
                    } else {
                        LOG.warn("Found unmarked record with no data. Deleting it...");
                        removeRecordById(recordId);
                    }
                }

                if (!logRecords.isEmpty()) {
                    updateBucketIdForRecords(currentBucketId, unmarkedRecordIds);
                    logBlock = new LogBlock(currentBucketId++, logRecords);

                    long logBlockSize = blockSize - leftBlockSize;
                    consumedSize -= logBlockSize;
                    consumedMemoryStorage.put(logBlock.getBlockId(), logBlockSize);

                    LOG.info("Created log block: id [{}], size {}. Log block record count: {}, Total record count: {}",
                            logBlock.getBlockId(), logBlockSize, logBlock.getRecords().size(), recordCount);
                } else {
                    LOG.info("No unmarked log records found");
                }

            } catch (SQLException e) {
                LOG.error("Can't retrieve unmarked records from storage", e);
            }
            return logBlock;
        }
    }

    private void removeRecordById(int recordId) {
        synchronized (connection) {
            LOG.trace("Removing log record with id [{}]", recordId);
            if (deleteByRecordIdStatement == null) {
                try {
                    deleteByRecordIdStatement = connection.prepareStatement(KAA_DELETE_BY_RECORD_ID);
                } catch (SQLException e) {
                    LOG.error("Can't create log remove statement", e);
                    throw new RuntimeException(e);
                }
            }

            try {
                deleteByRecordIdStatement.setInt(1, recordId);
                if (deleteByRecordIdStatement.executeUpdate() > 0) {
                    recordCount--;
                    LOG.info("Removed log record with id [{}]", recordId);
                } else {
                    LOG.warn("No log record was removed");
                }
            } catch (SQLException e) {
                LOG.error("Failed to remove a log record by recordId [{}]", recordId, e);
            }
        }
    }

    private void updateBucketIdForRecords(int bucketId, List<String> recordIds) {
        synchronized (connection) {
            LOG.trace("Updating bucket id [{}] for records with ids: {}", bucketId, recordIds);

            try {
                setBucketIdStatement = connection.prepareStatement(getUpdateBucketIdStatement(recordIds));
            } catch (SQLException e) {
                LOG.error("Can't create bucket id update statement", e);
                throw new RuntimeException(e);
            }

            try {
                setBucketIdStatement.setInt(1, bucketId);
                int affectedRows = setBucketIdStatement.executeUpdate();
                if (affectedRows > 0) {
                    LOG.trace("Successfully updated id [{}] for log records: {}", bucketId, affectedRows);
                } else {
                    LOG.info("No log records were updated");
                }
            } catch (SQLException e) {
                LOG.error("Failed to update bucket id [{}] for records with ids: {}", bucketId, recordIds, e);
            }
        }
    }

    private String getUpdateBucketIdStatement(List<String> recordIds) {
        String queryString = StringUtils.join(recordIds, ",");
        StringBuilder builder = new StringBuilder(KAA_UPDATE_BUCKET_ID);
        int indexOf = builder.lastIndexOf(SUBSTITUTE_SYMBOL);
        builder.replace(indexOf, indexOf + SUBSTITUTE_SYMBOL.length(), queryString);
        return builder.toString();
    }

    @Override
    public void removeRecordBlock(int recordBlockId) {
        synchronized (connection) {
            LOG.trace("Removing record block [{}] from storage", recordBlockId);
            if (deleteByBucketIdStatement == null) {
                try {
                    deleteByBucketIdStatement = connection.prepareStatement(KAA_DELETE_BY_BUCKET_ID);
                } catch (SQLException e) {
                    LOG.error("Can't create record block deletion statement", e);
                    throw new RuntimeException(e);
                }
            }

            try {
                deleteByBucketIdStatement.setInt(1, recordBlockId);
                int removedRecordsCount = deleteByBucketIdStatement.executeUpdate();
                if (removedRecordsCount > 0) {
                    recordCount -= removedRecordsCount;
                    LOG.info("Removed [{}] records from storage", recordCount);
                } else {
                    LOG.info("No records were removed from storage");
                }
            } catch (SQLException e) {
                LOG.error("Failed to remove record block with id [{}]", recordBlockId);
            }
        }
    }

    @Override
    public void notifyUploadFailed(int bucketId) {
        synchronized (connection) {
            LOG.trace("Notifying upload fail for bucket id: {}", bucketId);
            if (resetBucketIdStatement == null) {
                try {
                    resetBucketIdStatement = connection.prepareStatement(KAA_RESET_BY_BUCKET_ID);
                } catch (SQLException e) {
                    LOG.error("Can't create bucket id reset statement", e);
                    throw new RuntimeException(e);
                }
            }

            try {
                resetBucketIdStatement.setInt(1, bucketId);
                int affectedRows = resetBucketIdStatement.executeUpdate();
                if (affectedRows > 0) {
                    LOG.trace("Total [{}] log records reset for bucket id: [{}]", affectedRows, bucketId);
                } else {
                    LOG.warn("No log records for bucket with id: [{}]", bucketId);
                }

                long previouslyConsumedSize = consumedMemoryStorage.get(bucketId);
                consumedMemoryStorage.remove(bucketId);
                consumedSize += previouslyConsumedSize;
            } catch (SQLException e) {
                LOG.error("Failed to reset bucket with id [{}]", bucketId, e);
            }
        }
    }

    @Override
    public long getConsumedVolume() {
        return consumedSize;
    }

    @Override
    public long getRecordCount() {
        return recordCount;
    }

    private void initTable() throws SQLException {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.executeUpdate(KAA_CREATE_LOG_TABLE);
            statement.executeUpdate(KAA_CREATE_BUCKET_ID_INDEX);
        } finally {
            tryCloseStatement(statement);
        }
    }

    private void retrieveConsumedSizeAndVolume() throws SQLException {
        synchronized (connection) {
            Statement statement = null;
            try {
                statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(KAA_HOW_MANY_LOGS_IN_DB);
                rs.next();
                recordCount = rs.getLong(1);
                consumedSize = rs.getLong(2);
            } finally {
                tryCloseStatement(statement);
            }
        }
    }

    public void close() {
        try {
            if (insertStatement != null) {
                insertStatement.close();
            }

            if (deleteByRecordIdStatement != null) {
                deleteByRecordIdStatement.close();
            }

            if (deleteByBucketIdStatement != null) {
                deleteByBucketIdStatement.close();
            }

            if (setBucketIdStatement != null) {
                setBucketIdStatement.close();
            }

            if (resetBucketIdStatement != null) {
                resetBucketIdStatement.close();
            }

            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            LOG.error("Can't close SQLite db connection", e);
        }
    }

    private void tryCloseStatement(Statement statement) throws SQLException {
        if (statement != null) {
            statement.close();
        }
    }

    private void resetBucketIDs() throws SQLException {
        LOG.debug("Resetting bucket ids on application start");
        Statement statement = null;
        try {
            statement = connection.createStatement();
            int updatedRows = statement.executeUpdate(KAA_RESET_BUCKET_ID_ON_START);
            LOG.trace("Number of rows affected: {}", updatedRows);
        } catch (SQLException e) {
            LOG.error("Can't reset bucket IDs", e);
            throw new RuntimeException(e);
        } finally {
            tryCloseStatement(statement);
        }
    }
}
