/*
 * Copyright 2016 Redsaz <redsaz@gmail.com>.
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

import com.redsaz.lognition.api.LogsService;
import com.redsaz.lognition.api.exceptions.AppClientException;
import com.redsaz.lognition.api.exceptions.AppServerException;
import com.redsaz.lognition.api.labelselector.LabelSelectorExpression;
import com.redsaz.lognition.api.labelselector.LabelSelectorExpressionListener;
import com.redsaz.lognition.api.labelselector.LabelSelectorSyntaxException;
import com.redsaz.lognition.api.model.Label;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Log.Status;
import static com.redsaz.lognition.model.tables.Label.LABEL;
import static com.redsaz.lognition.model.tables.Log.LOG;
import com.redsaz.lognition.model.tables.records.LabelRecord;
import com.redsaz.lognition.model.tables.records.LogRecord;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep3;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.RecordHandler;
import org.jooq.RecordMapper;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.UpdateQuery;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores and accesses logs.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class JooqLogsService implements LogsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JooqLogsService.class);

    private static final RecordToLogMapper R2L = new RecordToLogMapper();
    private static final RecordToLabelMapper R2LABEL = new RecordToLabelMapper();

    private final ConnectionPool pool;
    private final SQLDialect dialect;
    private final String logsDir;

    /**
     * Create a new LogsService backed by a data store.
     *
     * @param jdbcPool opens connections to database
     * @param sqlDialect the type of SQL database that we should speak
     * @param logsDirectory the directory containing the logs
     */
    public JooqLogsService(ConnectionPool jdbcPool, SQLDialect sqlDialect, String logsDirectory) {
        pool = jdbcPool;
        dialect = sqlDialect;
        logsDir = logsDirectory;
    }

    @Override
    public Log create(Log source) {
        if (source == null) {
            throw new NullPointerException("No log information was specified.");
        } else if (source.getStatus() == null) {
            throw new NullPointerException("Log status must not be null.");
        } else if (source.getNotes() == null) {
            throw new NullPointerException("Log notes must not be null.");
        } else if (source.getName() == null) {
            throw new NullPointerException("Log title must not be null.");
        } else if (source.getUriName() == null) {
            throw new NullPointerException("Log uriName must not be null.");
        }

        LOGGER.info("Creating entry in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            LogRecord result = context.insertInto(LOG,
                    LOG.STATUS,
                    LOG.URI_NAME,
                    LOG.NAME,
                    LOG.DATA_FILE,
                    LOG.NOTES).values(
                            source.getStatus().ordinal(),
                            source.getUriName(),
                            source.getName(),
                            source.getDataFile(),
                            source.getNotes())
                    .returning().fetchOne();
            LOGGER.info("...Created log entry in DB.");
            return R2L.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to create log: " + ex.getMessage(), ex);
        }

    }

    @Override
    public InputStream getCsvContent(long id) throws IOException {
        Path dataPath = Path.of(logsDir, id + ".avro");
        InputStream is = Files.newInputStream(dataPath, StandardOpenOption.READ);
        return is;
    }

    @Override
    public File getAvroFile(long id) throws FileNotFoundException {
        Path dataPath = Path.of(logsDir, id + ".avro");
        if (!Files.exists(dataPath)) {
            throw new FileNotFoundException("No content file exists for " + dataPath.getFileName());
        }
        return dataPath.toFile();
    }

    @Override
    public Log get(long id) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            return context.selectFrom(LOG)
                    .where(LOG.ID.eq(id))
                    .fetchOne(R2L);
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get log_id=" + id + " because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<Log> list() {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            RecordsToListHandler<LogRecord, Log> r2lHandler = new RecordsToListHandler<>(R2L);
            return context.selectFrom(LOG).orderBy(LOG.ID.desc()).fetchInto(r2lHandler).getValues();
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get logs list");
        }
    }

    @Override
    public List<Long> listIdsBySelector(LabelSelectorExpression labelSelector) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            LabelSelectorToSelect ls2s = new LabelSelectorToSelect(context);
            labelSelector.consume(ls2s);
            Select<Record1<Long>> select = ls2s.getSelect();
            if (select == null) {
                return Collections.emptyList();
            }
            return select.fetch(LOG.ID);
        } catch (LabelSelectorSyntaxException ex) {
            throw new AppClientException("Label selector is invalid.", ex);
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get logs list");
        }
    }

    @Override
    public void delete(long id) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            context.delete(LOG).where(LOG.ID.eq(id)).execute();
        } catch (SQLException ex) {
            throw new AppServerException("Failed to delete log_id=" + id
                    + " because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Log update(Log source) {
        if (source == null) {
            throw new NullPointerException("No log information was specified.");
        }

        LOGGER.info("Updating entry in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            UpdateQuery<LogRecord> uq = context.updateQuery(LOG);
            if (source.getStatus() != null) {
                uq.addValue(LOG.STATUS, source.getStatus().ordinal());
            }
            if (source.getUriName() != null) {
                uq.addValue(LOG.URI_NAME, source.getUriName());
            }
            if (source.getDataFile() != null) {
                uq.addValue(LOG.DATA_FILE, source.getDataFile());
            }
            if (source.getNotes() != null) {
                uq.addValue(LOG.NOTES, source.getNotes());
            }
            if (source.getName() != null) {
                uq.addValue(LOG.NAME, source.getName());
            }
            if (source.getNotes() != null) {
                uq.addValue(LOG.NOTES, source.getNotes());
            }
            uq.addConditions(LOG.ID.eq(source.getId()));
            uq.setReturning();
            uq.execute();
            LogRecord result = uq.getReturnedRecord();
            LOGGER.info("...Updated entry in DB.");
            return R2L.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to update log: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void updateStatus(long id, Status newStatus) {
        LOGGER.info("Updating log id={} status={}...", id, newStatus);
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            context.update(LOG)
                    .set(LOG.STATUS, newStatus.ordinal())
                    .where(LOG.ID.eq(id))
                    .execute();
            LOGGER.info("...Updated log id={} status={}.", id, newStatus);
        } catch (SQLException ex) {
            LOGGER.error("...Failed to update log id={} status={}.", id, newStatus);
            throw new AppServerException("Failed to update log: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<Label> setLabels(long logId, Collection<Label> labels) {
        if (labels == null) {
            throw new NullPointerException("No labels were specified.");
        } else if (logId < 1L) {
            throw new IllegalArgumentException("Bad log id.");
        }

        LOGGER.info("Creating/Updating/Deleting labels in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            List<Label> toAdds = new ArrayList<>();
            List<Label> toChanges = new ArrayList<>();

            Map<String, Label> plannedLabels = labels.stream()
                    .collect(Collectors.toMap(Label::getKey, Function.identity()));
            Map<String, String> existingLabels = context.selectFrom(LABEL)
                    .where(LABEL.LOG_ID.eq(logId)).stream()
                    .collect(Collectors.toMap(LabelRecord::getKey, LabelRecord::getValue));

            plannedLabels.forEach((k, v) -> {
                String existingValue = existingLabels.get(k);
                if (existingValue == null) {
                    // If we don't have the label, we need to add it.
                    toAdds.add(v);
                } else if (!Objects.equals(v.getValue(), existingValue)) {
                    // If we have the label key, but value is different, change it.
                    toChanges.add(v);
                }
                // Otherwise, if we have the key, and the value is the same, don't change it.
            });

            // If the existing keys do not exist in the planned keys, then delete them.
            Set<String> toDeletes = existingLabels.keySet().stream()
                    .filter(k -> !plannedLabels.containsKey(k))
                    .collect(Collectors.toSet());

            // Insert new labels
            if (!toAdds.isEmpty()) {
                LOGGER.info("Inserting logId={} labels=\"{}\"", toAdds);
                InsertValuesStep3<LabelRecord, Long, String, String> insert = context.insertInto(LABEL)
                        .columns(LABEL.LOG_ID, LABEL.KEY, LABEL.VALUE);
                for (Label toAdd : toAdds) {
                    insert = insert.values(logId, toAdd.getKey(), toAdd.getValue());
                }
                insert.execute();
            }

            // Update changed labels
            if (!toChanges.isEmpty()) {
                LOGGER.info("Updating logId={} labels=\"{}\"", toChanges);
                toChanges.forEach((toChange) -> {
                    context.update(LABEL)
                            .set(LABEL.VALUE, toChange.getValue())
                            .where(LABEL.LOG_ID.eq(logId).and(LABEL.KEY.eq(toChange.getKey())))
                            .execute();
                });
            }

            // Delete removed labels
            if (!toDeletes.isEmpty()) {
                LOGGER.info("Deleting logId={} labels=\"{}\"", toDeletes);
                context.deleteFrom(LABEL)
                        .where(LABEL.LOG_ID.eq(logId).and(LABEL.KEY.in(toDeletes)))
                        .execute();

            }
            LOGGER.info("...Created/Updated/Deleted labels in DB.");
        } catch (SQLException ex) {
            throw new AppServerException("Failed to create timeseries: " + ex.getMessage(), ex);
        }
        return getLabels(logId);
    }

    @Override
    public List<Label> getLabels(long logId) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            RecordsToListHandler<LabelRecord, Label> r2lHandler = new RecordsToListHandler<>(R2LABEL);
            List<Label> labels = context.selectFrom(LABEL).where(LABEL.LOG_ID.eq(logId)).fetchInto(r2lHandler).getValues();
            Collections.sort(labels);
            return labels;
        } catch (SQLException ex) {
            throw new AppServerException("Failed to load labels for logId=" + logId, ex);
        }
    }

    private static class RecordToLogMapper implements RecordMapper<LogRecord, Log> {

        @Override
        public Log map(LogRecord record) {
            if (record == null) {
                return null;
            }
            return new Log(record.getId(),
                    Status.values()[record.getStatus()],
                    record.getUriName(),
                    record.getName(),
                    record.getDataFile(),
                    record.getNotes()
            );
        }
    }

    private static class RecordToLabelMapper implements RecordMapper<LabelRecord, Label> {

        @Override
        public Label map(LabelRecord record) {
            if (record == null) {
                return null;
            }
            return new Label(record.getKey(), record.getValue());
        }
    }

    private static class RecordsToListHandler<R extends Record, E extends Object> implements RecordHandler<R> {

        private final List<E> values = new ArrayList<>();
        private final RecordMapper<R, E> mapper;

        public RecordsToListHandler(RecordMapper<R, E> mapper) {
            this.mapper = mapper;
        }

        @Override
        public void next(R record) {
            values.add(mapper.map(record));
        }

        public List<E> getValues() {
            return values;
        }
    }

    private static class LabelSelectorToSelect implements LabelSelectorExpressionListener {

        private final List<Condition> conditions = new ArrayList<>();

        private final DSLContext context;

        public LabelSelectorToSelect(DSLContext inContext) {
            context = inContext;
        }

        @Override
        public void in(String labelName, List<String> labelValues) {
            Condition inCondition;
            if ("id".equals(labelName)) {
                Condition condition = LOG.ID.in(labelValues);
                inCondition = LOG.ID.in(context.selectDistinct(LOG.ID).from(LOG).where(condition));
            } else {
                Condition condition = LABEL.KEY.eq(labelName).and(LABEL.VALUE.in(labelValues));
                inCondition = LOG.ID.in(context.selectDistinct(LABEL.LOG_ID).from(LABEL).where(condition));
            }
            conditions.add(inCondition);
        }

        @Override
        public void notIn(String labelName, List<String> labelValues) {
            Condition inCondition;
            if ("id".equals(labelName)) {
                Condition condition = LOG.ID.notIn(labelValues);
                inCondition = LOG.ID.in(context.selectDistinct(LOG.ID).from(LOG).where(condition));
            } else {
                Condition condition = LABEL.KEY.eq(labelName).and(LABEL.VALUE.notIn(labelValues));
                inCondition = LOG.ID.in(context.selectDistinct(LABEL.LOG_ID).from(LABEL).where(condition));
            }
            conditions.add(inCondition);
        }

        @Override
        public void exists(String labelName) {
            if ("id".equals(labelName)) {
                // Do nothing. By definition, ALL logs have ids, so this is meaningless. We don't
                // really want to include all logs.
                return;
            }
            Condition condition = LABEL.KEY.eq(labelName);
            Condition inCondition = LOG.ID.in(context.selectDistinct(LABEL.LOG_ID).from(LABEL).where(condition));
            conditions.add(inCondition);
        }

        @Override
        public void notExists(String labelName) {
            if ("id".equals(labelName)) {
                // Do nothing. By definition, ALL logs have ids, so this is meaningless. We don't
                // really want to exclude all logs.
                return;
            }
            Condition condition = LABEL.KEY.eq(labelName);
            Condition inCondition = LOG.ID.in(context.selectDistinct(LABEL.LOG_ID).from(LABEL).whereNotExists(
                    context.selectDistinct(LABEL.LOG_ID).from(LABEL).where(condition)
            ));
            conditions.add(inCondition);
        }

        @Override
        public void equals(String labelName, String labelValue) {
            Condition inCondition;
            if ("id".equals(labelName)) {
                Condition condition = LOG.ID.eq(Long.valueOf(labelValue));
                inCondition = LOG.ID.in(context.selectDistinct(LOG.ID).from(LOG).where(condition));
            } else {
                Condition condition = LABEL.KEY.eq(labelName).and(LABEL.VALUE.eq(labelValue));
                inCondition = LOG.ID.in(context.selectDistinct(LABEL.LOG_ID).from(LABEL).where(condition));
            }
            conditions.add(inCondition);
        }

        @Override
        public void notEquals(String labelName, String labelValue) {
            Condition inCondition;
            if ("id".equals(labelName)) {
                Condition condition = LOG.ID.ne(Long.valueOf(labelValue));
                inCondition = LOG.ID.in(context.selectDistinct(LOG.ID).from(LOG).where(condition));
            } else {
                Condition condition = LABEL.KEY.eq(labelName).and(LABEL.VALUE.ne(labelValue));
                inCondition = LOG.ID.in(context.selectDistinct(LABEL.LOG_ID).from(LABEL).where(condition));
            }
            conditions.add(inCondition);
        }

        private Select<Record1<Long>> getSelect() {
            if (conditions.isEmpty()) {
                return null;
            }
            return context.selectDistinct(LOG.ID).from(LOG).where(conditions);
        }

    }
}
