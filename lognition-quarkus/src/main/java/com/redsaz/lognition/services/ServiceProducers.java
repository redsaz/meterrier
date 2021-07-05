/*
 * Copyright 2021 Redsaz <redsaz@gmail.com>.
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
package com.redsaz.lognition.services;

import com.redsaz.lognition.api.AttachmentsService;
import com.redsaz.lognition.api.ImportService;
import com.redsaz.lognition.api.LogsService;
import com.redsaz.lognition.api.ReviewsService;
import com.redsaz.lognition.api.StatsService;
import com.redsaz.lognition.store.ConnectionPool;
import com.redsaz.lognition.store.JooqAttachmentsService;
import com.redsaz.lognition.store.JooqImportService;
import com.redsaz.lognition.store.JooqLogsService;
import com.redsaz.lognition.store.JooqReviewsService;
import com.redsaz.lognition.store.JooqStatsService;
import com.redsaz.lognition.view.Processor;
import com.redsaz.lognition.view.Sanitizer;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Destroyed;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class ServiceProducers {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceProducers.class);
    private static final String LOGS_DIR = "./lognition-data/logs";
    private static final String ATTACHMENTS_DIR = "./lognition-data/attachmentsDir";
    private static final ConnectionPool POOL = ConnectionPoolInit.initPool();
    private static final AttachmentsService SANITIZER_ATTACHMENTS_SERVICE = new SanitizerAttachmentsService(new JooqAttachmentsService(POOL, SQLDialect.HSQLDB, ATTACHMENTS_DIR));
    private static final LogsService SANITIZER_LOGS_SERVICE = new SanitizerLogsService(new JooqLogsService(POOL, SQLDialect.HSQLDB, LOGS_DIR, SANITIZER_ATTACHMENTS_SERVICE));
    private static final ReviewsService SANITIZER_REVIEWS_SERVICE = new SanitizerReviewsService(new JooqReviewsService(POOL, SQLDialect.HSQLDB, SANITIZER_ATTACHMENTS_SERVICE));
    private static final ImportService SANITIZER_IMPORT_SERVICE = new SanitizerImportService(new JooqImportService(POOL, SQLDialect.HSQLDB));
    private static final StatsService STATS_SERVICE = new JooqStatsService(POOL, SQLDialect.HSQLDB);
    private static final ProcessorImportService PROCESSOR_IMPORT_SERVICE = new ProcessorImportService(
            SANITIZER_IMPORT_SERVICE, SANITIZER_LOGS_SERVICE, STATS_SERVICE, LOGS_DIR
    );

    @Produces
    @ApplicationScoped
    @Sanitizer
    public LogsService createSanitizerLogsService() {
        return SANITIZER_LOGS_SERVICE;
    }

    @Produces
    @ApplicationScoped
    @Sanitizer
    public ReviewsService createSanitizerReviewsService() {
        return SANITIZER_REVIEWS_SERVICE;
    }

    @Produces
    @ApplicationScoped
    @Processor
    public ImportService createProcessorImportService() {
        return PROCESSOR_IMPORT_SERVICE;
    }

    @Produces
    @ApplicationScoped
    public StatsService createStatsService() {
        return STATS_SERVICE;
    }

    public void init(@Observes @Initialized(ApplicationScoped.class) Object init) {
        SANITIZER_LOGS_SERVICE.get(-1L); // Grab any non-existing item from the service
        LOGGER.info("Started Lognition.");
    }

    public void destroy(@Observes @Destroyed(ApplicationScoped.class) Object init) {
        LOGGER.info("Shutting down Lognition.");
        PROCESSOR_IMPORT_SERVICE.shutdown();
    }

}
