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

import com.redsaz.lognition.api.AttachmentsService;
import com.redsaz.lognition.api.exceptions.AppClientException;
import com.redsaz.lognition.api.exceptions.AppServerException;
import com.redsaz.lognition.api.model.Attachment;
import static com.redsaz.lognition.model.tables.Attachment.ATTACHMENT;
import com.redsaz.lognition.model.tables.records.AttachmentRecord;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.RecordMapper;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores and accesses attachments.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class JooqAttachmentsService implements AttachmentsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JooqAttachmentsService.class);

    private static final RecordToAttachmentMapper R2A = new RecordToAttachmentMapper();

    private final ConnectionPool pool;
    private final SQLDialect dialect;
    private final File attachmentsDir;

    /**
     * Create a new AttachmentsService backed by a data store.
     *
     * @param jdbcPool opens connections to database
     * @param sqlDialect the type of SQL database that we should speak
     * @param attachmentsDirectory where all of the attachments will be stored.
     */
    public JooqAttachmentsService(ConnectionPool jdbcPool, SQLDialect sqlDialect,
            String attachmentsDirectory) {
        LOGGER.info("Using given Connection Pool.");
        pool = jdbcPool;
        dialect = sqlDialect;
        attachmentsDir = new File(attachmentsDirectory);
        try {
            Files.createDirectories(attachmentsDir.toPath());
        } catch (IOException ex) {
            throw new RuntimeException("Unable to create data directories.", ex);
        }
    }

    @Override
    public Attachment put(Attachment source, InputStream data) {
        if (data == null) {
            throw new NullPointerException("No attachment data provided.");
        }

        LOGGER.info("Receiving attachment...");
        long bytesRead = 0;
        File uploadDestFile = createUploadFile();
        LOGGER.info("Storing into {}", uploadDestFile.getAbsolutePath());
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(uploadDestFile))) {
            byte[] buff = new byte[4096];
            int num;
            while ((num = data.read(buff)) >= 0) {
                os.write(buff, 0, num);
                bytesRead += num;
            }
            os.flush();
        } catch (IOException ex) {
            LOGGER.error("Exception when uploading attachment.", ex);
            throw new AppServerException("Failed to upload content.", ex);
        }
        if (bytesRead == 0) {
            uploadDestFile.delete();
            throw new AppClientException("No data was uploaded.");
        }
        LOGGER.info("...Stored {} bytes into file {}.", bytesRead, uploadDestFile.getAbsolutePath());

        LOGGER.info("Creating DB entry for attachment.");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            AttachmentRecord result = context.insertInto(ATTACHMENT,
                    ATTACHMENT.OWNER,
                    ATTACHMENT.PATH,
                    ATTACHMENT.DESCRIPTION,
                    ATTACHMENT.UPLOADED_UTC_MILLIS)
                    .values(source.getOwner(),
                            source.getPath(),
                            source.getDescription(),
                            source.getUploadedUtcMillis())
                    .returning().fetchOne();
            LOGGER.info("Finished uploading attachment {} {}.", result.getId(), result.getPath());
            Attachment attachment = R2A.map(result);
            File attachmentFile = new File(attachmentsDir, Long.toString(attachment.getId()));
            if (attachmentFile.exists()) {
                LOGGER.info("Replacing attachment data for " + attachment);
            }
            Files.move(uploadDestFile.toPath(), attachmentFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return attachment;
        } catch (SQLException ex) {
            throw new AppServerException("Failed to create DB entry for attachment: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            if (uploadDestFile.exists()) {
                uploadDestFile.delete();
            }
            throw new AppServerException("Failed to receive attachment: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Attachment get(String owner, String path) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            AttachmentRecord result = context.selectFrom(ATTACHMENT)
                    .where(
                            ATTACHMENT.OWNER.eq(owner),
                            ATTACHMENT.OWNER.eq(path))
                    .fetchOne();
            return R2A.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Error when looking for attachment for owner=" + owner + " path=" + path, ex);
        }
    }

    @Override
    public InputStream getData(String owner, String path) {
        Attachment attachment = null;
        try {
            attachment = get(owner, path);
            if (attachment == null) {
                throw new AppClientException("No such attachment for owner=" + owner
                        + " path=" + path + " exists.");
            }
            File attachmentFile = new File(attachmentsDir, Long.toString(attachment.getId()));
            return new BufferedInputStream(new FileInputStream(attachmentFile));
        } catch (IOException ex) {
            throw new AppServerException("Failed to open stream for " + attachment, ex);
        }
    }

    @Override
    public List<Attachment> listForOwner(String owner) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            return context.selectFrom(ATTACHMENT)
                    .where(ATTACHMENT.OWNER.eq(owner))
                    .fetch(R2A);
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get attachments list because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void delete(String owner, String path) {
        Attachment attachment = get(owner, path);
        if (attachment == null) {
            throw new AppClientException("No such attachment for owner=" + owner
                    + " path=" + path + " exists.");
        }

        // Delete the data first, then the record.
        File attachmentFile = new File(attachmentsDir, Long.toString(attachment.getId()));
        if (attachmentFile.exists()) {
            if (!attachmentFile.delete()) {
                throw new AppServerException("Cannot delete " + attachment + " because "
                        + attachmentFile + " could not be deleted.");
            }
        } else {
            LOGGER.warn("Attachment data does not exist even though a record still exists for "
                    + attachment + ". Will delete record anyway.");
        }

        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            int numDeleted = context.deleteFrom(ATTACHMENT)
                    .where(ATTACHMENT.OWNER.eq(owner),
                            ATTACHMENT.PATH.eq(path))
                    .execute();
            if (numDeleted == 0) {
                LOGGER.info("No records erased for attachment for owner=" + owner + " path=" + path);
            }
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get attachments list because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void deleteForOwner(String owner) {
        listForOwner(owner)
                .forEach(att -> delete(att.getOwner(), att.getPath()));
    }

    @Override
    public Attachment update(Attachment source) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            AttachmentRecord result = context.update(ATTACHMENT)
                    .set(ATTACHMENT.DESCRIPTION, source.getDescription())
                    .set(ATTACHMENT.MIME_TYPE, source.getMimeType())
                    .where(
                            ATTACHMENT.OWNER.eq(source.getOwner()),
                            ATTACHMENT.OWNER.eq(source.getPath()))
                    .returning()
                    .fetchOne();
            return R2A.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Error when updating " + source, ex);
        }
    }

//    @Override
//    public Attachment get(long id) {
//        try (Connection c = pool.getConnection()) {
//            DSLContext context = DSL.using(c, dialect);
//            return context.selectFrom(ATTACHMENT)
//                    .where(ATTACHMENT.ID.eq(id))
//                    .fetchOne(R2A);
//        } catch (SQLException ex) {
//            throw new AppServerException("Cannot get attachment_id=" + id + " because: " + ex.getMessage(), ex);
//        }
//    }
//    @Override
//    public List<Attachment> getMany(Collection<Long> ids) {
//        try (Connection c = pool.getConnection()) {
//            DSLContext context = DSL.using(c, dialect);
//            return context.selectFrom(ATTACHMENT)
//                    .where(ATTACHMENT.ID.in(ids))
//                    .fetch(R2A);
//        } catch (SQLException ex) {
//            throw new AppServerException("Cannot get attachment_ids=" + ids + " because: " + ex.getMessage(), ex);
//        }
//    }
//
//    @Override
//    public List<Attachment> listAll() {
//        try (Connection c = pool.getConnection()) {
//            DSLContext context = DSL.using(c, dialect);
//            return context.selectFrom(ATTACHMENT).fetch(R2A);
//        } catch (SQLException ex) {
//            throw new AppServerException("Cannot get attachments list because: " + ex.getMessage(), ex);
//        }
//    }
//
//    @Override
//    public void delete(long id) {
//        try (Connection c = pool.getConnection()) {
//            DSLContext context = DSL.using(c, dialect);
//            LOGGER.info("Deleting attachment_id={}...", id);
//            File file = new File(Long.toString(id));
//            if (!file.delete()) {
//                LOGGER.error("Unable to delete attachment {}.", file);
//            }
//            int numDeleted = context.delete(ATTACHMENT).where(ATTACHMENT.ID.eq(id)).execute();
//            if (numDeleted > 0) {
//                LOGGER.info("...Deleted attachment_id={}.", id);
//            } else {
//                LOGGER.info("...No attachment_id={} was found in order to delete.");
//            }
//        } catch (SQLException ex) {
//            throw new AppServerException("Failed to delete attachment_id=" + id
//                    + " because: " + ex.getMessage(), ex);
//        }
//    }
//
//    @Override
//    public Attachment update(Attachment source) {
//        if (source == null) {
//            throw new NullPointerException("No attachment information was specified.");
//        }
//
//        LOGGER.debug("Updating entry in DB...");
//        try (Connection c = pool.getConnection()) {
//            DSLContext context = DSL.using(c, dialect);
//
//            UpdateSetMoreStep<AttachmentRecord> up = context.update(ATTACHMENT).set(ATTACHMENT.ID, source.getId());
//            if (source.getPath() != null) {
//                up.set(ATTACHMENT.PATHNAME, source.getPath());
//            }
//            if (source.getDescription() != null) {
//                up.set(ATTACHMENT.DESCRIPTION, source.getDescription());
//            }
//            if (source.getUploadedUtcMillis() != 0) {
//                up.set(IMPORT_INFO.UPLOADED_UTC_MILLIS, source.getUploadedUtcMillis());
//            }
//            AttachmentRecord result = up.where(ATTACHMENT.ID.eq(source.getId())).returning().fetchOne();
//            LOGGER.debug("...Updated entry in DB.");
//            return R2A.map(result);
//        } catch (SQLException ex) {
//            throw new AppServerException("Failed to update attachment: " + ex.getMessage(), ex);
//        }
//    }
    private File createUploadFile() {
        try {
            return File.createTempFile("log-", ".tmp", attachmentsDir);
        } catch (IOException ex) {
            LOGGER.error("Exception when creating upload file.", ex);
            throw new AppServerException("Failed to upload content.", ex);
        }
    }

    private static class RecordToAttachmentMapper implements RecordMapper<AttachmentRecord, Attachment> {

        @Override
        public Attachment map(AttachmentRecord record) {
            if (record == null) {
                return null;
            }
            return new Attachment(record.getId(),
                    record.getOwner(),
                    record.getPath(),
                    record.getDescription(),
                    record.getMimeType(),
                    record.getUploadedUtcMillis());
        }
    }

//    private static class AttachmentRecordsToListHandler implements RecordHandler<AttachmentRecord> {
//
//        private final List<Attachment> attachments = new ArrayList<>();
//
//        @Override
//        public void next(AttachmentRecord record) {
//            attachments.add(R2A.map(record));
//        }
//
//        public List<Attachment> getAttachments() {
//            return attachments;
//        }
//    }
}
