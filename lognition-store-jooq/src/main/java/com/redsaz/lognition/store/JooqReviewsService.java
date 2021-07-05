/*
 * Copyright 2018 Redsaz <redsaz@gmail.com>.
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

import com.redsaz.lognition.api.AttachmentsService;
import com.redsaz.lognition.api.ReviewsService;
import com.redsaz.lognition.api.exceptions.AppServerException;
import com.redsaz.lognition.api.model.Attachment;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Review;
import static com.redsaz.lognition.model.tables.Log.LOG;
import static com.redsaz.lognition.model.tables.Review.REVIEW;
import static com.redsaz.lognition.model.tables.ReviewLog.REVIEW_LOG;
import com.redsaz.lognition.model.tables.records.LogRecord;
import com.redsaz.lognition.model.tables.records.ReviewLogRecord;
import com.redsaz.lognition.model.tables.records.ReviewRecord;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep2;
import org.jooq.Record;
import org.jooq.RecordHandler;
import org.jooq.RecordMapper;
import org.jooq.SQLDialect;
import org.jooq.UpdateQuery;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores and accesses reviews.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class JooqReviewsService implements ReviewsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JooqReviewsService.class);

    private static final RecordToReviewMapper R2R = new RecordToReviewMapper();
    private static final LogRecordToLogMapper LR2L = new LogRecordToLogMapper();
    private static final RecordToLogMapper R2L = new RecordToLogMapper();

    private final ConnectionPool pool;
    private final SQLDialect dialect;
    private final AttachmentsService attSvc;

    /**
     * Create a new ReviewsService backed by a data store.
     *
     * @param jdbcPool opens connections to database
     * @param sqlDialect the type of SQL database that we should speak
     */
    public JooqReviewsService(ConnectionPool jdbcPool, SQLDialect sqlDialect,
            AttachmentsService attachmentsService) {
        pool = jdbcPool;
        dialect = sqlDialect;
        attSvc = attachmentsService;
    }

    @Override
    public Review create(Review source) {
        if (source == null) {
            throw new NullPointerException("No review information was specified.");
        } else if (source.getDescription() == null) {
            throw new NullPointerException("Review description must not be null.");
        } else if (source.getName() == null) {
            throw new NullPointerException("Review name must not be null.");
        } else if (source.getUriName() == null) {
            throw new NullPointerException("Review uriName must not be null.");
        } else if (source.getBody() == null) {
            throw new NullPointerException("Review body must not be null.");
        }

        LOGGER.info("Creating entry in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            ReviewRecord result = context.insertInto(REVIEW,
                    REVIEW.URI_NAME,
                    REVIEW.NAME,
                    REVIEW.DESCRIPTION,
                    REVIEW.CREATED_MILLIS,
                    REVIEW.LAST_UPDATED_MILLIS,
                    REVIEW.BODY).values(
                            source.getUriName(),
                            source.getName(),
                            source.getDescription(),
                            source.getCreatedMillis(),
                            source.getLastUpdatedMillis(),
                            source.getBody())
                    .returning().fetchOne();
            LOGGER.info("...Created review entry in DB.");
            return R2R.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to create review: " + ex.getMessage(), ex);
        }

    }

    @Override
    public Review get(long id) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            return context.selectFrom(REVIEW)
                    .where(REVIEW.ID.eq(id))
                    .fetchOne(R2R);
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get review_id=" + id + " because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<Review> list() {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            RecordsToListHandler<ReviewRecord, Review> r2lHandler = new RecordsToListHandler<>(R2R);
            return context.selectFrom(REVIEW).orderBy(REVIEW.LAST_UPDATED_MILLIS.desc()).fetchInto(r2lHandler).getValues();
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get reviews list");
        }
    }

    @Override
    public void delete(long id) {
        // Delete any owned attac=hments first before deleting the review itself.
        attSvc.deleteForOwner(toOwner(id));

        // Now delete the review.
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            context.delete(REVIEW).where(REVIEW.ID.eq(id)).execute();
        } catch (SQLException ex) {
            throw new AppServerException("Failed to delete review_id=" + id
                    + " because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Review update(Review source) {
        if (source == null) {
            throw new NullPointerException("No review information was specified.");
        }

        LOGGER.info("Updating entry in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            UpdateQuery<ReviewRecord> uq = context.updateQuery(REVIEW);
            if (source.getUriName() != null) {
                uq.addValue(REVIEW.URI_NAME, source.getUriName());
            }
            if (source.getName() != null) {
                uq.addValue(REVIEW.NAME, source.getName());
            }
            if (source.getDescription() != null) {
                uq.addValue(REVIEW.DESCRIPTION, source.getDescription());
            }
            uq.addValue(REVIEW.CREATED_MILLIS, source.getCreatedMillis());
            if (source.getLastUpdatedMillis() != null) {
                uq.addValue(REVIEW.LAST_UPDATED_MILLIS, source.getLastUpdatedMillis());
            }
            if (source.getBody() != null) {
                uq.addValue(REVIEW.BODY, source.getBody());
            }
            uq.addConditions(REVIEW.ID.eq(source.getId()));
            uq.execute();
            LOGGER.info("...Updated entry in DB.");
            return context.selectFrom(REVIEW)
                    .where(REVIEW.ID.eq(source.getId()))
                    .fetchOne(R2R);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to update review: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void setReviewLogs(long reviewId, Collection<Long> logIds) {
        if (logIds == null) {
            throw new NullPointerException("No logs were specified.");
        } else if (reviewId < 1L) {
            throw new IllegalArgumentException("Bad review id.");
        }

        LOGGER.info("Creating/Updating/Deleting logs with reviews in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            Set<Long> existingLogRefs = context.select(REVIEW_LOG.LOG_ID).from(REVIEW_LOG)
                    .where(REVIEW_LOG.REVIEW_ID.eq(reviewId)).stream()
                    .map(v -> v.get(REVIEW_LOG.LOG_ID))
                    .collect(Collectors.toSet());

            List<Long> toAdds = logIds.stream()
                    .filter(v -> !existingLogRefs.contains(v))
                    .collect(Collectors.toList());

            // If the existing logRefs do not exist in the planned refs, then delete them.
            Set<Long> toDeletes = existingLogRefs.stream()
                    .filter(v -> !logIds.contains(v))
                    .collect(Collectors.toSet());

            // Insert new labels
            if (!toAdds.isEmpty()) {
                LOGGER.info("Inserting reviewId={} logIds={}", toAdds);
                InsertValuesStep2<ReviewLogRecord, Long, Long> insert = context.insertInto(REVIEW_LOG)
                        .columns(REVIEW_LOG.REVIEW_ID, REVIEW_LOG.LOG_ID);
                for (Long toAdd : toAdds) {
                    insert = insert.values(reviewId, toAdd);
                }
                insert.execute();
            }

            // Delete removed labels
            if (!toDeletes.isEmpty()) {
                LOGGER.info("Deleting reviewId={} logIds={}", toDeletes);
                context.deleteFrom(REVIEW_LOG)
                        .where(REVIEW_LOG.REVIEW_ID.eq(reviewId).and(REVIEW_LOG.LOG_ID.in(toDeletes)))
                        .execute();

            }
            LOGGER.info("...Created/Updated/Deleted review logs in DB.");
        } catch (SQLException ex) {
            throw new AppServerException("Failed to create timeseries: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<Log> getReviewLogs(long reviewId) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            RecordsToListHandler<Record, Log> r2lHandler = new RecordsToListHandler<>(R2L);
            List<Log> logs = context.select(LOG.fields()).from(LOG).join(REVIEW_LOG).on(REVIEW_LOG.LOG_ID.eq(LOG.ID)).where(REVIEW_LOG.REVIEW_ID.eq(reviewId)).fetchInto(r2lHandler).getValues();
            return logs;
        } catch (SQLException ex) {
            throw new AppServerException("Failed to load labels for reviewId=" + reviewId, ex);
        }
    }

    @Override
    public Attachment putAttachment(long reviewId, Attachment source, InputStream data) {
        source = new Attachment(0, toOwner(reviewId), source.getPath(), source.getDescription(),
                source.getMimeType(), source.getUploadedUtcMillis());
        return attSvc.put(source, data);
//        try (Connection c = pool.getConnection()) {
//            DSLContext context = DSL.using(c, dialect);
//
//            // ERR, WAIT. SHOULD THiS BE A TABLE OF IDS? OR SHOULD IT BE REVIEW_ID, PATHNAME?
//            // ANOTHER ERR WAIT. WHAT IF WE MADE AN OWNER COLUMN IN THE ATTACHMENT TABLE? WE COULD
//            // FORGO THIS TRY CATCH ENTIRELY.
//            context.insertInto(REVIEW_ATTACHMENT)
//                    .columns(REVIEW_ATTACHMENT.REVIEW_ID, REVIEW_ATTACHMENT.ATTACHMENT_ID)
//                    .values(reviewId, stored.getId())
//                    .execute();
//        } catch (SQLException ex) {
//            throw new AppServerException("Failed to associate attachmentId=" + stored.getId()
//                    + " with reviewId=" + reviewId, ex);
//        }
//
//        return stored;
    }

    @Override
    public InputStream getAttachmentData(long reviewId, String attachmentPath) {
        return attSvc.getData(toOwner(reviewId), attachmentPath);
    }

    @Override
    public List<Attachment> listAttachments(long reviewId) {
        return attSvc.listForOwner(toOwner(reviewId));
    }

    @Override
    public void deleteAttachment(long reviewId, String attachmentPath) {
        attSvc.delete(toOwner(reviewId), attachmentPath);
    }

    private static String toOwner(long reviewId) {
        return "reviews/" + reviewId;
    }

    private static class RecordToReviewMapper implements RecordMapper<ReviewRecord, Review> {

        @Override
        public Review map(ReviewRecord record) {
            if (record == null) {
                return null;
            }
            return new Review(record.getId(),
                    record.getUriName(),
                    record.getName(),
                    record.getDescription(),
                    record.getCreatedMillis(),
                    record.getLastUpdatedMillis(),
                    record.getBody()
            );
        }
    }

    private static class LogRecordToLogMapper implements RecordMapper<LogRecord, Log> {

        @Override
        public Log map(LogRecord record) {
            if (record == null) {
                return null;
            }
            return new Log(record.getId(),
                    Log.Status.values()[record.getStatus()],
                    record.getUriName(),
                    record.getName(),
                    record.getDataFile(),
                    record.getNotes()
            );
        }
    }

    private static class RecordToLogMapper implements RecordMapper<Record, Log> {

        @Override
        public Log map(Record record) {
            if (record == null) {
                return null;
            }
            LogRecord lr = record.into(LOG);
            return LR2L.map(lr);
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

}
