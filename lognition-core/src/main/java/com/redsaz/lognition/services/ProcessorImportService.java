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
package com.redsaz.lognition.services;

import com.redsaz.lognition.api.ImportService;
import com.redsaz.lognition.api.LogsService;
import com.redsaz.lognition.api.StatsService;
import com.redsaz.lognition.api.model.CodeCounts;
import com.redsaz.lognition.api.model.ImportInfo;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Sample;
import com.redsaz.lognition.api.model.Stats;
import com.redsaz.lognition.api.model.Timeseries;
import com.redsaz.lognition.convert.AvroSamplesWriter;
import com.redsaz.lognition.convert.CsvJtlSource;
import com.redsaz.lognition.convert.Samples;
import com.redsaz.lognition.convert.SamplesWriter;
import com.redsaz.lognition.stats.StatsBuilder;
import com.redsaz.lognition.stats.StatsBuilder.StatsItems;
import com.redsaz.lognition.store.ConnectionPool;
import com.redsaz.lognition.store.JooqImportService;
import com.redsaz.lognition.store.JooqLogsService;
import com.redsaz.lognition.store.JooqStatsService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does not directly store logs, but is responsible for ensuring that the logs and metadata sent to
 * and retrieved from the store are correctly formatted, sized, and without malicious/errorific
 * content.
 *
 * Default values for jtl files:
 * timestamp,elapsed,label,responseCode,responseCode,responseMessage,threadName,dataType,success,bytes,grpThreads,allThreads,Latency
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class ProcessorImportService implements ImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorImportService.class);
    private static final long OVERALL_LABEL_ID = 0; // label ID for "Overall" category.
    private static final long DEFAULT_SPAN_MILLIS = 60000L;

    private final ImportService srv;
    private final LogsService logsSrv;
    private final StatsService statsSrv;
    private final String convertedDir;
    private final Importer importer;
    private final Thread importerThread;

    public static void main(String[] args) throws Exception {
        final ConnectionPool pool = ConnectionPoolInit.initPool();
        final ImportService saniImportSrv = new SanitizerImportService(new JooqImportService(pool, SQLDialect.HSQLDB));
        final String convertedDir = "jtls/target/logs";
        final LogsService saniLogSrv = new SanitizerLogsService(
                new JooqLogsService(pool, SQLDialect.HSQLDB, convertedDir, null));
        final StatsService jooqStatsSrv = new JooqStatsService(pool, SQLDialect.HSQLDB);
        final long now = System.currentTimeMillis();

        Importer imp = new Importer(saniImportSrv, saniLogSrv, jooqStatsSrv, convertedDir);
        Thread impThread = new Thread(imp, "LogImporter-" + System.identityHashCode(imp));
        impThread.start();

        ImportInfo ii = new ImportInfo(now, "jtls/target/real-without-header.jtl", now);
        imp.addJob(ii);

        imp.shutdown();
    }

    public ProcessorImportService(ImportService importService, LogsService logsService,
            StatsService statsService, String convertedDirectory) {
        srv = importService;
        logsSrv = logsService;
        statsSrv = statsService;
        convertedDir = convertedDirectory;
        importer = new Importer(srv, logsSrv, statsSrv, convertedDir);
        importerThread = new Thread(importer, "LogImporter-" + System.identityHashCode(importer));
        init();
    }

    @Override
    public void delete(long id) {
        srv.delete(id);
    }

    @Override
    public ImportInfo get(long id) {
        return srv.get(id);
    }

    @Override
    public List<ImportInfo> list() {
        return srv.list();
    }

    @Override
    public ImportInfo upload(InputStream raw, Log log, String importedFilename, long uploadedUtcMillis) {
        logsSrv.updateStatus(log.getId(), Log.Status.UPLOADING);
        try {
            ImportInfo result = srv.upload(raw, log, importedFilename, uploadedUtcMillis);
            importer.addJob(result);
            return result;
        } catch (Exception ex) {
            logsSrv.updateStatus(log.getId(), Log.Status.UPLOAD_FAILED);
            throw ex;
        }
    }

    @Override
    public ImportInfo update(ImportInfo source) {
        ImportInfo result = srv.update(source);
        importer.addJob(result);
        return result;
    }

    public void shutdown() {
        importer.shutdown();
    }

    private void init() {
        try {
            Files.createDirectories(new File(convertedDir).toPath());
        } catch (IOException ex) {
            String msg = "Could not create directories for " + convertedDir + "!";
            LOGGER.error(msg, ex);
            throw new RuntimeException(msg, ex);
        }
        importerThread.start();
    }

    private static class Importer implements Runnable {

        private final ImportService importSrv;
        private final LogsService logsSrv;
        private final StatsService statsSrv;
        private final String convertedDir;
        private final BlockingQueue<ImportInfo> awaitingImport = new LinkedBlockingQueue<>();
        private final AtomicBoolean shutdown = new AtomicBoolean();

        public Importer(ImportService importService, LogsService logsService,
                StatsService statsService, String convertedDirectory) {
            importSrv = importService;
            logsSrv = logsService;
            statsSrv = statsService;
            convertedDir = convertedDirectory;
        }

        public void addJob(ImportInfo info) {
            logsSrv.updateStatus(info.getId(), Log.Status.QUEUED);
            awaitingImport.add(info);
        }

        @Override
        public void run() {
            while (!Thread.interrupted() && !shutdown.get()) {
                try {
                    ImportInfo source = awaitingImport.take();
                    processImport(source);
                } catch (InterruptedException ex) {
                    LOGGER.info("Interrupted while importing file. Closing thread.");
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    LOGGER.error("Unhandled exception while importing file: " + ex.getMessage(), ex);
                }
            }
        }

        private void processImport(ImportInfo source) {
            Samples sourceSamples = null;
            try {
                LOGGER.info("...importing...");
                logsSrv.updateStatus(source.getId(), Log.Status.IMPORTING);
                sourceSamples = new CsvJtlSource(new File(source.getImportedFilename()));
                SamplesWriter writer = new AvroSamplesWriter();

                File avro = new File(convertedDir, String.format("%d.avro", source.getId()));
                String hash = writer.write(sourceSamples, avro);
                LOGGER.info("...SHA-256: {}...", hash);
            } catch (IOException ex) {
                logsSrv.updateStatus(source.getId(), Log.Status.IMPORT_FAILED);
                LOGGER.error("Could not import " + source.getImportedFilename(), ex);

                return;
            }

            logsSrv.updateStatus(source.getId(), Log.Status.COMPLETE);
            LOGGER.info("...imported log id={}.", source.getId());

            importSrv.delete(source.getId());

            eagerCalculateStats(source, sourceSamples);
        }

        private void eagerCalculateStats(ImportInfo source, Samples sourceSamples) {
            // label, samples, average, median, p90, p95, p99, min, max, error %, throughput,
            try {
                long logId = source.getId();
                CodeCounts overallCodeCounts = StatsBuilder.calcAggregateCounts(sourceSamples.getSamples());
                CodeCounts overallCodeCountsTimeseries = StatsBuilder.calcTimeseriesCounts(sourceSamples.getSamples(), DEFAULT_SPAN_MILLIS);
                Timeseries overall = StatsBuilder.calcTimeseriesStats(sourceSamples.getSamples(), DEFAULT_SPAN_MILLIS);
                Stats overallAggregate = StatsBuilder.calcAggregateStats(sourceSamples.getSamples());
                StatsItems histAndPercs = StatsBuilder.calcHistogram(sourceSamples.getSamples());

                Map<String, List<Sample>> labelsSamples = StatsBuilder.sortAndSplitByLabel(sourceSamples.getSamples());

                List<String> labels = new ArrayList<>(labelsSamples.size() + 1);
                labels.add("Overall"); // Overall is always labelId=0
                labels.addAll(sourceSamples.getLabels());
                statsSrv.createSampleLabels(logId, labels);

                statsSrv.createOrUpdateCodeCounts(logId, OVERALL_LABEL_ID, overallCodeCounts);
                statsSrv.createOrUpdateCodeCounts(logId, OVERALL_LABEL_ID, overallCodeCountsTimeseries);
                statsSrv.createOrUpdateTimeseries(logId, OVERALL_LABEL_ID, overall);
                statsSrv.createOrUpdateAggregate(logId, OVERALL_LABEL_ID, overallAggregate);
                statsSrv.createOrUpdateHistogram(logId, OVERALL_LABEL_ID, histAndPercs.getHistogram());
                statsSrv.createOrUpdatePercentiles(logId, OVERALL_LABEL_ID, histAndPercs.getPercentiles());

                for (int labelId = 1; labelId < labels.size(); ++labelId) {
                    String label = labels.get(labelId);
                    List<Sample> labelSamples = labelsSamples.get(label);
                    if (labelSamples == null) {
                        LOGGER.warn("Encountered null logId={} labelId={} while eagerly calculating stats, which shouldn't happen! Skipping.", logId, labelId);
                        continue;
                    }
                    CodeCounts labelCodeCounts = StatsBuilder.calcAggregateCounts(labelSamples);
                    CodeCounts labelCodeCountsTimeseries = StatsBuilder.calcTimeseriesCounts(labelSamples, DEFAULT_SPAN_MILLIS);
                    Timeseries labelTimeseries = StatsBuilder.calcTimeseriesStats(labelSamples, DEFAULT_SPAN_MILLIS);
                    Stats labelAggregate = StatsBuilder.calcAggregateStats(labelSamples);
                    histAndPercs = StatsBuilder.calcHistogram(labelSamples);

                    statsSrv.createOrUpdateCodeCounts(logId, labelId, labelCodeCounts);
                    statsSrv.createOrUpdateCodeCounts(logId, labelId, labelCodeCountsTimeseries);
                    statsSrv.createOrUpdateTimeseries(logId, labelId, labelTimeseries);
                    statsSrv.createOrUpdateAggregate(logId, labelId, labelAggregate);
                    statsSrv.createOrUpdateHistogram(logId, labelId, histAndPercs.getHistogram());
                    statsSrv.createOrUpdatePercentiles(logId, labelId, histAndPercs.getPercentiles());
                }
            } catch (Exception ex) {
                LOGGER.error("Hit exception while calculating stats for log id={}. No more stats will be eagerly processed for this log.", source.getId(), ex);
            }
        }

        /**
         * Signals the instance that once it has finished work on the current item, it is to stop.
         * If there are any additionl items waiting in the queue, they will not be processed.
         */
        public void shutdown() {
            shutdown.set(true);
        }
    }
}
