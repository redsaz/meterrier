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
package com.redsaz.lognition.view;

import com.redsaz.lognition.api.ImportService;
import com.redsaz.lognition.api.LognitionMediaType;
import com.redsaz.lognition.api.LogsService;
import com.redsaz.lognition.api.ReviewsService;
import com.redsaz.lognition.api.labelselector.LabelSelectorExpression;
import com.redsaz.lognition.api.labelselector.LabelSelectorSyntaxException;
import com.redsaz.lognition.api.model.Label;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Review;
import com.redsaz.lognition.convert.AvroToCsvJtlConverter;
import com.redsaz.lognition.services.LabelSelectorParser;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An endpoint for accessing log. Many of the REST endpoints and browser endpoints are identical
 * where possible; look at docs/endpoints.md for why.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
@Path("/logs")
public class LogsResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogsResource.class);

    private ReviewsService reviewsSrv;
    private LogsService logsSrv;
    private ImportService importSrv;
    private static final ExecutorService REVIEWS_CALC_EXEC = Executors.newSingleThreadExecutor();
    private static final AvroToCsvJtlConverter CONVERTER = new AvroToCsvJtlConverter();

    public LogsResource() {
    }

    @Inject
    public LogsResource(@Sanitizer ReviewsService reviewsService,
            @Sanitizer LogsService logsService, @Processor ImportService importService) {
        reviewsSrv = reviewsService;
        logsSrv = logsService;
        importSrv = importService;
    }

    /**
     * Lists logs URI and titles. If given a selector then logs with matching labels are listed. If
     * no selector is given then all logs are listed.
     *
     * @param labelSelector label selector for logs
     * @return Logs, by URI and title.
     */
    @GET
    @Produces({LognitionMediaType.LOGBRIEF_V1_JSON, MediaType.APPLICATION_JSON})
    public Response listLogBriefs(@QueryParam("labelSelector") String labelSelector) {
        List<Log> logs;
        if (labelSelector == null) {
            logs = logsSrv.list();
        } else {
            LabelSelectorExpression lse = LabelSelectorParser.parse(labelSelector);
            List<Long> logIds = logsSrv.listIdsBySelector(lse);
            logs = logIds.stream()
                    .map(logsSrv::get)
                    .collect(Collectors.toList());
        }
        return Response.ok(logs).build();
    }

    /**
     * Get the note contents.
     *
     * @param id The id of the note.
     * @return Note.
     */
    @GET
    @Produces({LognitionMediaType.LOGBRIEF_V1_JSON, MediaType.APPLICATION_JSON})
    @Path("{id}")
    public Response getLogBrief(@PathParam("id") long id) {
        Log brief = logsSrv.get(id);
        if (brief == null) {
            throw new NotFoundException("Could not find log brief id=" + id);
        }
        return Response.ok(brief).build();
    }

    /**
     * Download the log data in CSV form.
     *
     * @param id The id of the log.
     * @return log data.
     */
    @GET
    @Produces({"text/csv"})
    @Path("{id}/content")
    public Response getCsvContent(@PathParam("id") long id) throws IOException {
        try {
            File file = logsSrv.getAvroFile(id);
            return Response.ok(CONVERTER.convertStreaming(file), "text/csv").build();
        } catch (FileNotFoundException ex) {
            throw new NotFoundException(ex.getMessage());
        }
    }

    @POST
    @Consumes({MediaType.APPLICATION_OCTET_STREAM, "text/csv", MediaType.TEXT_PLAIN,
        MediaType.TEXT_XML, MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON,
        "application/zip"})
    @Produces({LognitionMediaType.LOGBRIEF_V1_JSON, MediaType.APPLICATION_JSON})
    public Response importLog(InputStream source,
            @QueryParam("name") String name, @QueryParam("notes") String notes,
            @QueryParam("labels") String labelsText) {
        if (name == null) {
            name = "uploaded";
        }
        List<Label> labels = toLabelsList(labelsText);

        Log sourceLog = new Log(0L, Log.Status.AWAITING_UPLOAD, null, name, null, notes);
        Log resultLog = logsSrv.create(sourceLog);
        if (!labels.isEmpty()) {
            logsSrv.setLabels(resultLog.getId(), labels);
        }

        REVIEWS_CALC_EXEC.execute(() -> {
            calculateAllReviewLogs();
        });

        return Response.status(Status.CREATED).entity(importSrv.upload(source, resultLog, name, System.currentTimeMillis())).build();
    }

    @DELETE
    @Path("{id}")
    public Response deleteLog(@PathParam("id") long id) {
        logsSrv.delete(id);
        return Response.status(Status.NO_CONTENT).build();
    }

    private void calculateAllReviewLogs() {
        for (Review review : reviewsSrv.list()) {
            try {
                String body = review.getBody();
                LabelSelectorExpression labelSelector = LabelSelectorParser.parse(body);
                List<Long> logIds = logsSrv.listIdsBySelector(labelSelector);

                reviewsSrv.setReviewLogs(review.getId(), logIds);
            } catch (LabelSelectorSyntaxException ex) {
                LOGGER.error("Review_id={} has a syntax error with it's label selector. No more logs will be added to the review until fixed.", review.getId());
            } catch (RuntimeException ex) {
                LOGGER.error("Exception when using label selector from review_id={}.", ex);
            }
        }
    }

    private static List<Label> toLabelsList(String labelsText) {
        LOGGER.info("Labelizing labels=\"{}\"", labelsText);
        if (labelsText == null || labelsText.isEmpty()) {
            return Collections.emptyList();
        }

        String[] pairs = labelsText.split("(?:,|\\s)+");
        List<Label> labels = new ArrayList<>(pairs.length);
        for (String pair : pairs) {
            Label label = toLabel(pair);
            labels.add(label);
        }

        LOGGER.info("Labelized labels: {}", labels);
        return labels;
    }

    private static Label toLabel(String labelText) {
        String[] keyval = labelText.split("=", 2);
        if (keyval.length != 2) {
            return new Label(keyval[0], "");
        }
        return new Label(keyval[0], keyval[1]);
    }

}
