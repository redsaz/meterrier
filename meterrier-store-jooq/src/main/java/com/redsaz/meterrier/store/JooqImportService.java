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
package com.redsaz.meterrier.store;

import com.redsaz.meterrier.api.ImportService;
import com.redsaz.meterrier.api.exceptions.AppServerException;
import com.redsaz.meterrier.api.model.ImportInfo;
import static com.redsaz.meterrier.model.tables.PendingImport.PENDING_IMPORT;
import com.redsaz.meterrier.model.tables.records.PendingImportRecord;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.RecordHandler;
import org.jooq.RecordMapper;
import org.jooq.SQLDialect;
import org.jooq.UpdateSetMoreStep;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores and accesses log imports.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class JooqImportService implements ImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JooqImportService.class);

    private static final RecordToImportMapper R2I = new RecordToImportMapper();

    private final ConnectionPool pool;
    private final SQLDialect dialect;
    private final File uploadedLogsDir;

    /**
     * Create a new ImportService backed by a data store.
     *
     * @param jdbcPool opens connections to database
     * @param sqlDialect the type of SQL database that we should speak
     */
    public JooqImportService(ConnectionPool jdbcPool, SQLDialect sqlDialect) {
        LOGGER.info("Using given Connection Pool.");
        pool = jdbcPool;
        dialect = sqlDialect;
        uploadedLogsDir = new File("./meterrier-data/uploaded-logs");
        try {
            Files.createDirectories(uploadedLogsDir.toPath());
        } catch (IOException ex) {
            throw new RuntimeException("Unable to create data directories.", ex);
        }
    }

    @Override
    public ImportInfo upload(InputStream raw, ImportInfo source) {
        if (raw == null) {
            throw new NullPointerException("No import was specified.");
        } else if (source == null) {
            throw new NullPointerException("No import information was specified.");
        }

        LOGGER.info("Storing uploaded file...");
        long bytesRead = 0;
        File destFile = getUploadFile(source);
        LOGGER.info("Storing into {}", destFile.getAbsolutePath());
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(destFile))) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buff = new byte[4096];
            int num;
            while ((num = raw.read(buff)) >= 0) {
                md.update(buff, 0, num);
                os.write(buff, 0, num);
                bytesRead += num;
            }
            os.flush();
        } catch (IOException | NoSuchAlgorithmException ex) {
            LOGGER.error("Exception when uploading log.", ex);
            throw new AppServerException("Failed to upload content.", ex);
        }
        LOGGER.info("...Stored {} bytes into file {}.", bytesRead, destFile.getAbsolutePath());

        LOGGER.info("Creating entry in DB...");
        LOGGER.info("Import: {}", source);
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            // TODO make relative to storage.
            PendingImportRecord result = context.insertInto(PENDING_IMPORT,
                    PENDING_IMPORT.IMPORTED_FILENAME,
                    PENDING_IMPORT.TITLE,
                    PENDING_IMPORT.USER_SPECIFIED_TYPE,
                    PENDING_IMPORT.UPLOADED_UTC_MILLIS)
                    .values(destFile.getAbsolutePath(),
                            source.getTitle(),
                            source.getUserSpecifiedType(),
                            source.getUploadedUtcMillis())
                    .returning().fetchOne();
            LOGGER.info("...Created entry in DB.");
            LOGGER.info("Finished uploading import {} {}.", result.getId(), result.getImportedFilename());
            return R2I.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to create import: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ImportInfo get(long id) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            return context.selectFrom(PENDING_IMPORT)
                    .where(PENDING_IMPORT.ID.eq(id))
                    .fetchOne(R2I);
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get import_id=" + id + " because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<ImportInfo> list() {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            PendingimportRecordsToListHandler r2iHandler = new PendingimportRecordsToListHandler();
            return context.selectFrom(PENDING_IMPORT).fetchInto(r2iHandler).getImports();
        } catch (SQLException ex) {
            throw new AppServerException("Cannot get imports list because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void delete(long id) {
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);
            LOGGER.debug("Deleting import_id={}...", id);
            ImportInfo info = get(id);
            if (info == null) {
                LOGGER.info("No such import_id={} exists.", id);
                return;
            }
            File file = new File(info.getImportedFilename());
            if (!file.delete()) {
                LOGGER.error("Unable to delete imported file {}.", file);
            }
            context.delete(PENDING_IMPORT).where(PENDING_IMPORT.ID.eq(id)).execute();
            LOGGER.debug("...Deleted import_id={}.", id);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to delete pendingimport_id=" + id
                    + " because: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ImportInfo update(ImportInfo source) {
        if (source == null) {
            throw new NullPointerException("No import information was specified.");
        }

        LOGGER.debug("Updating entry in DB...");
        try (Connection c = pool.getConnection()) {
            DSLContext context = DSL.using(c, dialect);

            UpdateSetMoreStep<PendingImportRecord> up = context.update(PENDING_IMPORT).set(PENDING_IMPORT.ID, source.getId());
            if (source.getImportedFilename() != null) {
                up.set(PENDING_IMPORT.IMPORTED_FILENAME, source.getImportedFilename());
            }
            if (source.getTitle() != null) {
                up.set(PENDING_IMPORT.TITLE, source.getTitle());
            }
            if (source.getUserSpecifiedType() != null) {
                up.set(PENDING_IMPORT.USER_SPECIFIED_TYPE, source.getUserSpecifiedType());
            }
            if (source.getUploadedUtcMillis() != 0) {
                up.set(PENDING_IMPORT.UPLOADED_UTC_MILLIS, source.getUploadedUtcMillis());
            }
            PendingImportRecord result = up.where(PENDING_IMPORT.ID.eq(source.getId())).returning().fetchOne();
            LOGGER.debug("...Updated entry in DB.");
            return R2I.map(result);
        } catch (SQLException ex) {
            throw new AppServerException("Failed to update import: " + ex.getMessage(), ex);
        }
    }

    private File getUploadFile(ImportInfo info) {
        try {
            return File.createTempFile("log-", ".tmp", uploadedLogsDir);
        } catch (IOException ex) {
            LOGGER.error("Exception when creating upload file.", ex);
            throw new AppServerException("Failed to upload content.", ex);
        }
    }

    final protected static char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static class RecordToImportMapper implements RecordMapper<PendingImportRecord, ImportInfo> {

        @Override
        public ImportInfo map(PendingImportRecord record) {
            if (record == null) {
                return null;
            }
            return new ImportInfo(record.getId(),
                    record.getImportedFilename(),
                    record.getTitle(),
                    record.getUserSpecifiedType(),
                    record.getUploadedUtcMillis(),
                    null
            );
        }
    }

    private static class PendingimportRecordsToListHandler implements RecordHandler<PendingImportRecord> {

        private final List<ImportInfo> imports = new ArrayList<>();

        @Override
        public void next(PendingImportRecord record) {
            imports.add(R2I.map(record));
        }

        public List<ImportInfo> getImports() {
            return imports;
        }
    }
}
