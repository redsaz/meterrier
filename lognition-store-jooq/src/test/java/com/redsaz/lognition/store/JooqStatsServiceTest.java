/*
 * Copyright 2020 Redsaz <redsaz@gmail.com>.
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
package com.redsaz.lognition.store;

import com.redsaz.lognition.api.exceptions.AppServerException;
import com.redsaz.lognition.api.model.CodeCounts;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Log.Status;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.hsqldb.jdbc.JDBCPool;
import org.jooq.SQLDialect;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * @author Redsaz <redsaz@gmail.com>
 */
public class JooqStatsServiceTest {

    @Rule
    public TemporaryFolder connectionDir = new TemporaryFolder();

    /**
     * Aggregate code counts are code count totals spanning the entire log.
     */
    private static final CodeCounts CODE_COUNTS = new CodeCounts.Builder(0L)
            .increment("200").commitBin().build();

    @Test
    public void testCreateOrUpdateCodeCounts() throws IOException, SQLException {
        try (CloseableConnectionPool cp = createConnectionPool()) {
            // Given a log with a label,
            JooqStatsService unit = new JooqStatsService(cp, SQLDialect.HSQLDB);
            JooqLogsService logSvc = new JooqLogsService(cp, SQLDialect.HSQLDB,
                    connectionDir.newFolder().toString(), null);
            Log log = new Log(1L, Status.COMPLETE, "test", "Test", "test.jtl", "notes");
            log = logSvc.create(log);
            unit.createSampleLabels(log.getId(), Collections.singletonList("overall"));

            // When aggregate code counts are stored,
            unit.createOrUpdateCodeCounts(log.getId(), 0L, CODE_COUNTS);

            // Then retrieving the code counts will match the source.
            CodeCounts actual = unit.getCodeCounts(log.getId(), 0L, 0L);
            assertEquals(CODE_COUNTS.getCodes(), actual.getCodes());
            assertEquals(CODE_COUNTS.getCounts(), actual.getCounts());
            assertEquals(CODE_COUNTS.getSpanMillis(), actual.getSpanMillis());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateOrUpdateCodeCounts_badLogId() throws IOException, SQLException {
        try (CloseableConnectionPool cp = createConnectionPool()) {
            JooqStatsService unit = new JooqStatsService(cp, SQLDialect.HSQLDB);

            // Given a bad log ID (an ID of 0 or below),
            long badLogId = 0L;

            // When attempting to create code counts,
            unit.createOrUpdateCodeCounts(badLogId, 0L, CODE_COUNTS);

            // Then an illegal argument exception is thrown. (Checked by test harness)
        }
    }

    @Test(expected = NullPointerException.class)
    public void testCreateOrUpdateCodeCounts_nullCounts() throws IOException, SQLException {
        try (CloseableConnectionPool cp = createConnectionPool()) {
            JooqStatsService unit = new JooqStatsService(cp, SQLDialect.HSQLDB);

            // Given a null CodeCounts,
            // When attempting to create code counts,
            unit.createOrUpdateCodeCounts(1L, 0L, null);

            // Then a null pointer exception is thrown. (Checked by test harness)
        }
    }

    @Test
    public void testGetCodeCounts_bogusLogId() throws IOException, SQLException {
        try (CloseableConnectionPool cp = createConnectionPool()) {
            JooqStatsService unit = new JooqStatsService(cp, SQLDialect.HSQLDB);

            // Given a non-existing log ID,
            long bogusLogId = 1;

            // When attempting to retrieve code counts,
            CodeCounts actual = unit.getCodeCounts(bogusLogId, 0L, 0L);

            // Then null is returned.
            assertNull("Should be null if log id was not found.", actual);
        }
    }

    @Test
    public void testGetCodeCountsForLog() throws IOException, SQLException {
        // Given a log with an overall label and two individual labels,
        Log log = new Log(1, Status.COMPLETE, "test", "Test", "test.jtl", "notes");

        // and aggregate code counts for two different sampler labels (including "overall" for both)
        CodeCounts aggregateOverall = new CodeCounts.Builder(0L)
                .increment("200").increment("200")
                .increment("500").increment("500")
                .commitBin().build();

        CodeCounts aggregateLabel1 = new CodeCounts.Builder(0L)
                .increment("200").increment("200")
                .commitBin().build();

        CodeCounts aggregateLabel2 = new CodeCounts.Builder(0L)
                .increment("500").increment("500")
                .commitBin().build();

        // and timeseries code counts for two different sampler labels (and the overall label)
        CodeCounts timeseriesOverall = new CodeCounts.Builder(15_000L)
                .increment("200").increment("500").commitBin() // First 15s bin
                .increment("200").increment("500").commitBin() // Second 15s bin
                .build();

        CodeCounts timeseriesLabel1 = new CodeCounts.Builder(15_000L)
                .increment("200").commitBin() // First 15s bin
                .increment("200").commitBin() // Second 15s bin
                .build();

        CodeCounts timeseriesLabel2 = new CodeCounts.Builder(15_000L)
                .increment("500").commitBin() // First 15s bin
                .increment("500").commitBin() // Second 15s bin
                .build();

        try (CloseableConnectionPool cp = createConnectionPool()) {
            JooqStatsService unit = new JooqStatsService(cp, SQLDialect.HSQLDB);
            JooqLogsService logSvc = new JooqLogsService(cp, SQLDialect.HSQLDB,
                    connectionDir.newFolder().toString(), null);
            log = logSvc.create(log);
            unit.createSampleLabels(log.getId(), Arrays.asList("overall", "label1", "label2"));

            // When aggregate code counts are stored,
            unit.createOrUpdateCodeCounts(log.getId(), 0, aggregateOverall);
            unit.createOrUpdateCodeCounts(log.getId(), 1, aggregateLabel1);
            unit.createOrUpdateCodeCounts(log.getId(), 2, aggregateLabel2);
            // and timeseries code counts are stored,
            unit.createOrUpdateCodeCounts(log.getId(), 0, timeseriesOverall);
            unit.createOrUpdateCodeCounts(log.getId(), 1, timeseriesLabel1);
            unit.createOrUpdateCodeCounts(log.getId(), 2, timeseriesLabel2);

            // Then calling getCodeCountsForLog with the logId and span_millis of 0 will return a
            // map that matches all three aggregate code counts,
            Map<Long, CodeCounts> actualAgg = unit.getCodeCountsForLog(log.getId(), 0);
            assertEquals(aggregateOverall.getCodes(), actualAgg.get(0L).getCodes());
            assertEquals(aggregateOverall.getCounts(), actualAgg.get(0L).getCounts());
            assertEquals(aggregateOverall.getSpanMillis(), actualAgg.get(0L).getSpanMillis());

            assertEquals(aggregateLabel1.getCodes(), actualAgg.get(1L).getCodes());
            assertEquals(aggregateLabel1.getCounts(), actualAgg.get(1L).getCounts());
            assertEquals(aggregateLabel1.getSpanMillis(), actualAgg.get(1L).getSpanMillis());

            assertEquals(aggregateLabel2.getCodes(), actualAgg.get(2L).getCodes());
            assertEquals(aggregateLabel2.getCounts(), actualAgg.get(2L).getCounts());
            assertEquals(aggregateLabel2.getSpanMillis(), actualAgg.get(2L).getSpanMillis());

            // and calling getCodeCountsForLog with the logId and span_millis of 15_000 will return
            // a map that matches all three timeseries code counts.
            Map<Long, CodeCounts> actualTs = unit.getCodeCountsForLog(log.getId(), 15_000);
            assertEquals(timeseriesOverall.getCodes(), actualTs.get(0L).getCodes());
            assertEquals(timeseriesOverall.getCounts(), actualTs.get(0L).getCounts());
            assertEquals(timeseriesOverall.getSpanMillis(), actualTs.get(0L).getSpanMillis());

            assertEquals(timeseriesLabel1.getCodes(), actualTs.get(1L).getCodes());
            assertEquals(timeseriesLabel1.getCounts(), actualTs.get(1L).getCounts());
            assertEquals(timeseriesLabel1.getSpanMillis(), actualTs.get(1L).getSpanMillis());

            assertEquals(timeseriesLabel2.getCodes(), actualTs.get(2L).getCodes());
            assertEquals(timeseriesLabel2.getCounts(), actualTs.get(2L).getCounts());
            assertEquals(timeseriesLabel2.getSpanMillis(), actualTs.get(2L).getSpanMillis());
        }
    }

    private CloseableConnectionPool createConnectionPool() throws IOException {
        File hsqldbFile = connectionDir.newFile();
        JDBCPool jdbc = new JDBCPool();
        jdbc.setUrl("jdbc:hsqldb:" + hsqldbFile.toURI() + ";shutdown=true;hsqldb.lob_file_scale=4;hsqldb.lob_compressed=true");
        jdbc.setUser("SA");
        jdbc.setPassword("SA");

        try (Connection c = jdbc.getConnection()) {
            DbInitializer.initDb(c);
        } catch (SQLException ex) {
            throw new AppServerException("Cannot initialize logs service: " + ex.getMessage(), ex);
        }
        return new CloseableConnectionPool(jdbc);
    }

    private class CloseableConnectionPool implements ConnectionPool, AutoCloseable {

        private final JDBCPool pool;

        public CloseableConnectionPool(JDBCPool jdbcPool) {
            pool = jdbcPool;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return pool.getConnection();
        }

        @Override
        public void close() throws SQLException {
            pool.close(1);
        }
    }
}
