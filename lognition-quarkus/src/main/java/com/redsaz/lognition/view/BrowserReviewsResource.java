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

import com.github.slugify.Slugify;
import com.redsaz.lognition.api.LogsService;
import com.redsaz.lognition.api.ReviewsService;
import com.redsaz.lognition.api.StatsService;
import com.redsaz.lognition.api.exceptions.AppClientException;
import com.redsaz.lognition.api.labelselector.LabelSelectorExpression;
import com.redsaz.lognition.api.labelselector.LabelSelectorExpressionFormatter;
import com.redsaz.lognition.api.labelselector.LabelSelectorSyntaxException;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Percentiles;
import com.redsaz.lognition.api.model.Review;
import com.redsaz.lognition.api.model.Stats;
import com.redsaz.lognition.services.LabelSelectorParser;
import com.redsaz.lognition.view.model.Chart;
import io.vertx.core.http.HttpServerRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.interpolation.UnivariateInterpolator;
import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An endpoint for accessing reviews. The REST endpoints and browser endpoints are identical; look
 * at docs/endpoints.md for why.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
@Path("/reviews")
public class BrowserReviewsResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserReviewsResource.class);

    private ReviewsService reviewsSrv;
    private LogsService logsSrv;
    private StatsService statsSrv;
    private Templater cfg;

    private static final Parser CM_PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder().escapeHtml(true).build();
    private static final Slugify SLG = new Slugify();

    private static final ThreadLocal<NumberFormat> PERC_FORMAT = new ThreadLocal<NumberFormat>() {
        @Override
        protected NumberFormat initialValue() {
            return new DecimalFormat("0.00");
        }
    };

    public BrowserReviewsResource() {
    }

    @Inject
    public BrowserReviewsResource(@Sanitizer ReviewsService reviewsService,
            @Sanitizer LogsService logsService, StatsService statsService, Templater config) {
        logsSrv = logsService;
        reviewsSrv = reviewsService;
        statsSrv = statsService;
        cfg = config;
    }

    /**
     * Presents a web page of reviews.
     *
     * @param httpRequest The request for the page.
     * @return Web page of reviews.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response listReviews(@Context HttpServerRequest httpRequest) {
        String dist = "/dist";
        List<Review> reviews = reviewsSrv.list();
        LOGGER.info("Reviews: {}", reviews);

        Map<String, Object> root = new HashMap<>();
        root.put("reviews", reviews);
        root.put("base", "");
        root.put("dist", dist);
        root.put("title", "Reviews");
        root.put("content", "review-list.ftl");
        return Response.ok(cfg.buildFromTemplate(root, "page.ftl")).build();
    }

    /**
     * Presents a web page for viewing a specific review.
     *
     * @param reviewId The id of the review.
     * @return Review view page.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{id}")
    public Response getReviewById(@PathParam("id") long reviewId) {
        return getReview(reviewId, null);
    }

    /**
     * Presents a web page for viewing a specific review.
     *
     * @param reviewId The id of the review.
     * @param urlName The urlName of the review.
     * @return Review view page.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{id}/{urlName}")
    public Response getReviewByIdWithName(@PathParam("id") long reviewId,
            @PathParam("urlName") String urlName) {
        return getReview(reviewId, urlName);
    }

    @POST
    @Consumes("multipart/form-data")
    @Produces({MediaType.TEXT_HTML})
    public Response finishCreateReview(MultipartInput input) {
        LOGGER.info("Creating review...");
        try {
            String name = null;
            String description = null;
            String body = null;
            ContentDispositionSubParts subParts = new ContentDispositionSubParts();
            for (InputPart part : input.getParts()) {
                subParts.clear();
                String partContentDisposition = part.getHeaders().getFirst("Content-Disposition");
                parseContentDispositionHeader(partContentDisposition, subParts);
                switch (subParts.getName()) {
                    case "name":
                        try {
                        name = part.getBodyAsString();
                    } catch (IOException ex) {
                        LOGGER.error("Error getting name.", ex);
                    }
                    break;
                    case "description":
                        try {
                        description = part.getBodyAsString();
                    } catch (IOException ex) {
                        LOGGER.error("Error getting description.", ex);
                    }
                    break;
                    case "body":
                        try {
                        body = part.getBodyAsString();
                        body = LabelSelectorExpressionFormatter.format(LabelSelectorParser.parse(body));
                    } catch (IOException ex) {
                        LOGGER.error("Error getting body.", ex);
                    } catch (LabelSelectorSyntaxException ex) {
                        LOGGER.error("Label selector syntax error. The label selector will not be used.");
                        body = null;
                    }
                    break;
                    default: {
                        // Skip it, we don't use it.
                        LOGGER.info("Skipped part={}", subParts.getName());
                    }
                    break;
                }
            }
            Review review = new Review(0, null, name, description,
                    ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond(), null, body);
            Review result = reviewsSrv.create(review);
            calculateReviewLogs(result);
            Response resp = Response.seeOther(URI.create("reviews")).build();
            LOGGER.info("Finished creating review {}", result);
            return resp;
        } catch (RuntimeException ex) {
            LOGGER.error("BAD STUFF:" + ex.getMessage(), ex);
            throw ex;
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    /**
     * Presents a web page for creating a review.
     *
     * @return review creation page.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("create")
    public Response createReview() {
        String dist = "/dist";
        Map<String, Object> root = new HashMap<>();
        root.put("base", "");
        root.put("dist", dist);
        root.put("title", "Create Review");
        root.put("content", "review-create.ftl");
        return Response.ok(cfg.buildFromTemplate(root, "page.ftl")).build();
    }

    /**
     * Presents a web page for editing a review.
     *
     * @param reviewId the review to edit.
     * @return review edit page.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{id}/edit")
    public Response editReview(@PathParam("id") long reviewId) {
        String dist = "/dist";

        Map<String, Object> root = new HashMap<>();
        Review review = reviewsSrv.get(reviewId);
        if (review == null) {
            throw new NotFoundException("Could not find reviewId=" + reviewId);
        }

        LOGGER.info("Editing review={}", review);

        root.put("review", review);
        root.put("base", "");
        root.put("dist", dist);
        root.put("title", "Edit Review");
        root.put("content", "review-edit.ftl");
        return Response.ok(cfg.buildFromTemplate(root, "page.ftl")).build();
    }

    @POST
    @Consumes("multipart/form-data")
    @Produces(MediaType.TEXT_HTML)
    @Path("{id}")
    public Response finishEditReview(MultipartInput input, @PathParam("id") long reviewId) {
        LOGGER.info("Submitting review {} edits...", reviewId);
        try {
            String name = null;
            String description = null;
            String body = null;
            ContentDispositionSubParts subParts = new ContentDispositionSubParts();
            for (InputPart part : input.getParts()) {
                subParts.clear();
                String partContentDisposition = part.getHeaders().getFirst("Content-Disposition");
                parseContentDispositionHeader(partContentDisposition, subParts);
                switch (subParts.getName()) {
                    case "name":
                        try {
                        name = part.getBodyAsString();
                    } catch (IOException ex) {
                        LOGGER.error("Error getting name.", ex);
                    }
                    break;
                    case "description":
                        try {
                        description = part.getBodyAsString();
                    } catch (IOException ex) {
                        LOGGER.error("Error getting description.", ex);
                    }
                    break;
                    case "body":
                        try {
                        body = part.getBodyAsString();
                        body = LabelSelectorExpressionFormatter.format(LabelSelectorParser.parse(body));
                    } catch (IOException ex) {
                        LOGGER.error("Error getting body.", ex);
                    } catch (LabelSelectorSyntaxException ex) {
                        LOGGER.error("Label selector syntax error. The label selector will not be used.");
                        body = null;
                    }
                    break;
                    default: {
                        // Skip it, we don't use it.
                        LOGGER.info("Skipped part={}", subParts.getName());
                    }
                    break;
                }
            }
            Review review = new Review(reviewId, null, name, description,
                    ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond(), null, body);
            Review result = reviewsSrv.update(review);
            calculateReviewLogs(result);
            Response resp = Response.seeOther(URI.create("reviews/" + reviewId)).build();
            LOGGER.info("Finished updating review {}.", reviewId);
            return resp;
        } catch (RuntimeException ex) {
            LOGGER.error("BAD STUFF:" + ex.getMessage(), ex);
            throw ex;
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    @POST
    @Path("delete")
    public Response deleteReviewForm(@FormParam("id") long id) {
        reviewsSrv.delete(id);
        Response resp = Response.seeOther(URI.create("/reviews")).build();
        return resp;
    }

    @GET
    @Path("{id}/attachments/{attachmentPath:.*}")
    @Produces(MediaType.WILDCARD)
    public Response getAttachment(@PathParam("id") long id,
            @PathParam("attachmentPath") String attachmentPath) {
        return Response.ok(reviewsSrv.getAttachmentData(id, attachmentPath)).build();
//        return Response.ok(new File("lognition-data/custom/reviews/1/" + resource)).build();
    }

    /**
     * Uploads one or more attachments for a review.
     *
     * @param id Identifier of the review
     * @param input The attachment info and contents.
     * @return A redirect response back to the review.
     */
    @POST
    @Consumes("multipart/form-data")
    @Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_JSON})
    @Path("{id}/res")
    public Response uploadAttachments(@PathParam("id") long id, MultipartInput input) {
        LOGGER.info("Uploading attachments for review {}", id);

        /*
        Anyway,
        It's possible to upload a bunch of images at once, and specify the pathnames for each.
        We should do that here, but also in the creation of the review itself.
        And in the edit screen for the review, those attachments should be listed? Right?
         */
        try {
            String filename = null;
            ContentDispositionSubParts subParts = new ContentDispositionSubParts();
            for (InputPart part : input.getParts()) {
                subParts.clear();
                String partContentDisposition = part.getHeaders().getFirst("Content-Disposition");
                parseContentDispositionHeader(partContentDisposition, subParts);
                switch (subParts.getName()) {
                    case "content":
                        try (InputStream contentStream = part.getBody(InputStream.class, null)) {
                        LOGGER.info("Retrieving filename...");
                        filename = subParts.getFilename();
                        LOGGER.info("Uploading content from filename={}...", filename);

                        try {
                            sourceLog = new Log(0L, Log.Status.AWAITING_UPLOAD, null, name, null, notes);
                            resultLog = logsSrv.create(sourceLog);

                            content = importSrv.upload(contentStream, resultLog, filename, updateMillis);
                            LOGGER.info("Uploaded content from {}.", filename);
                            LOGGER.info("Created import_id={}.", content.getId());
                        } catch (AppClientException ex) {
                            if (resultLog != null) {
                                logsSrv.delete(resultLog.getId());
                            }
                            throw ex;
                        }
                        break;
                    } catch (IOException ex) {
                        LOGGER.error("BAD STUFF:" + ex.getMessage(), ex);
                        Response resp = Response.serverError().entity(ex).build();
                        return resp;
                    }
                    default: {
                        // Skip it, we don't use it.
                        LOGGER.info("Skipped part={}", subParts.getName());
                    }
                    break;
                }
            }

            if (labels != null) {
                logsSrv.setLabels(resultLog.getId(), labels);
            }

            // If the name or notes came in AFTER the content was uploaded and the log created, then
            // update the log's name and notes.
            if (!Objects.equals(name, sourceLog.getName())
                    || !Objects.equals(notes, sourceLog.getNotes())) {
                Log updatedLog = new Log(resultLog.getId(), null, name, name, resultLog.getDataFile(), notes);
                resultLog = logsSrv.update(updatedLog);
            }

            REVIEWS_CALC_EXEC.execute(() -> {
                calculateAllReviewLogs();
            });

            Response resp = Response.seeOther(URI.create("logs")).build();
            LOGGER.info("Finished uploading log {} for import", content);
            return resp;
        } catch (RuntimeException ex) {
            LOGGER.error("BAD STUFF:" + ex.getMessage(), ex);
            throw ex;
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    /**
     * Presents a web page for viewing a specific review. The actual review displayed only really
     * depends on the review id, the URL name is optional. If the given URL name doesn't match the
     * real URL name of the review with the id, then a redirect will happen.
     *
     * @param req The request for the page.
     * @param reviewId The id of the review.
     * @param uriName The optional review URI name. May be null.
     * @return Review view page if the urlName matches the real urlName for the review with the id.
     */
    private Response getReview(long reviewId, String uriName) {
        String dist = "/dist";
        Review review = reviewsSrv.get(reviewId);
        if (review == null) {
            throw new NotFoundException("Could not find reviewId=" + reviewId);
        } else if (!Objects.equals(uriName, review.getUriName())
                && review.getUriName() != null
                && !review.getUriName().isEmpty()) {
            LOGGER.debug("reviewId={} provided name was \"{}\" but expected \"{}\".", reviewId, uriName, review.getUriName());
            return Response.seeOther(URI.create("reviews/" + reviewId + "/" + review.getUriName()))
                    .status(Response.Status.MOVED_PERMANENTLY)
                    .build();
        }
        List<Log> briefs = reviewsSrv.getReviewLogs(reviewId);

        List<Chart> reviewGraphs = createReviewCharts(briefs);

        Map<String, Object> root = new HashMap<>();
        root.put("review", review);
        root.put("briefs", briefs);
        root.put("base", "");
        root.put("dist", dist);
        root.put("title", review.getName());
        root.put("descriptionHtml", commonMarkToHtml(review.getDescription()));
        root.put("content", "review-view.ftl");
        root.put("reviewGraphs", reviewGraphs);
        root.put("custom", "res/custom.jpg");
        return Response.ok(cfg.buildFromTemplate(root, "page.ftl")).build();
    }

    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Disposition
    private static void parseContentDispositionHeader(String headerValue, NameValueListener listener) {
        // Examples:
        // form-data; name="content"; filename="file-asdf.txt"
        // form-data; name="content"; filename="file-with-quote\"-asdf.txt"
        // form-data; name="content"; filename="file-with-backslash\-asdf.txt"
        // form-data; name="content"; filename="file-with-backslash-and-quote\\"-asdf.txt"
        // Basically, if a backslash is encountered, check the next char. If
        // the next char is a quote, ignore the backslash, otherwise keep it.
        listener.beginParse();
        int cursor = headerValue.indexOf(";");
        if (cursor < 0) {
            listener.endParse();
            return;
        }
        ++cursor; // Skip the first entry because it is usually "form-data;"
        String[] nameValPair = new String[]{null, null};
        for (int length = headerValue.length(); cursor < length && cursor >= 0;) {
            cursor = getNameValue(headerValue, cursor, nameValPair);
            // -1 means that, though there was more string available,
            // there were no name-value pairs.
            if (cursor >= 0) {
                listener.nameValue(nameValPair[0], nameValPair[1]);
                // Now find the next semi-colon (or end-of-message)
            }
        }
        listener.endParse();
    }

    private static int getNameValue(String source, int cursor, String[] outNameValPair) {
        outNameValPair[0] = outNameValPair[1] = null;
        // First, skip to the first non-semicolon or space.
        for (int length = source.length(); cursor < length; ++cursor) {
            char curr = source.charAt(cursor);
            if (curr != ';' & curr != ' ') {
                break;
            }
        }
        // Now find the name part.
        int equalsPos = source.indexOf("=", cursor);
        // If no key-value pairs were found, return -1.
        if (equalsPos < 0) {
            return -1;
        }
        outNameValPair[0] = source.substring(cursor, equalsPos).trim();
        cursor = ++equalsPos;
        // Now, find the value part.
        StringBuilder value = new StringBuilder();
        boolean insideQuotes = false;
        for (int length = source.length(); cursor < length; ++cursor) {
            char curr = source.charAt(cursor);
            if (curr == '"') {
                // If we already were inside the quotes, then it means we
                // reached the second quotes mark, so advance cursor to the pos
                // after the quote and leave.
                if (insideQuotes) {
                    ++cursor;
                    break;
                }
                // otherwite, this is the first quote, and we should start
                // making the value string.
                insideQuotes = true;
            } else if (insideQuotes) {
                // Handle special case: if backslash appears, and then next
                // char is a quote, then append the quote, not the backslash.
                if (curr == '\\') {
                    if (cursor < length && source.charAt(cursor + 1) == '"') {
                        ++cursor;
                        curr = '"';
                    }
                }
                value.append(curr);
            }
        }
        outNameValPair[1] = value.toString();
        return cursor;
    }

    private static final Stats EMPTY_STAT = new Stats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    private static final Percentiles EMPTY_PERCENTILES = null;
    private static final Metrics EMPTY_METRICS = new Metrics(EMPTY_STAT, EMPTY_PERCENTILES);

    private static final UnivariateInterpolator INTERP = new ConstantOrLinearInterpolator();

    private static final double[] PERCENTILE_POINTS = new double[]{
        0d, 1d, 2d, 3d, 4d, 5d, 6d, 7d, 8d, 9d,
        10d, 11d, 12d, 13d, 14d, 15d, 16d, 17d, 18d, 19d,
        20d, 21d, 22d, 23d, 24d, 25d, 26d, 27d, 28d, 29d,
        30d, 31d, 32d, 33d, 34d, 35d, 36d, 37d, 38d, 39d,
        40d, 41d, 42d, 43d, 44d, 45d, 46d, 47d, 48d, 49d,
        50d, 51d, 52d, 53d, 54d, 55d, 56d, 57d, 58d, 59d,
        60d, 61d, 62d, 63d, 64d, 65d, 66d, 67d, 68d, 69d,
        70d, 71d, 72d, 73d, 74d, 75d, 76d, 77d, 78d, 79d,
        80d, 81d, 82d, 83d, 84d, 85d, 86d, 87d, 88d, 89d,
        90d, 91d, 92d, 93d, 94d, 95d, 96d, 97d, 97.5d, 98d, 98.25d, 98.5d, 98.75d, 99d,
        99.125d, 99.25d, 99.375d, 99.5d, 99.625d, 99.75d, 99.875d, 99.9d, 99.99d, 99.999, 99.9999d,
        100d};

    /**
     * Combines a list of different {@link Percentiles} into a two-dimensional array of values,
     * sorted by percentile. This is required because different series can have differently sized
     * percentiles.
     *
     * @param percentiles List of percentiles to combine into 2d array
     * @return 2d array of percentiles
     */
    private static SortedMap<Double, List<Long>> unifyPercentiles(List<Percentiles> percentiles) {
        SortedMap<Double, List<Long>> unified = new TreeMap<>();
        for (int i = 0; i < percentiles.size(); ++i) {
            Percentiles percs = percentiles.get(i);
            if (percs == null) {
                continue;
            }
            List<Double> originalPercs = percs.getPercentiles();
            List<Long> originalMillis = percs.getValues();
            List<Double> savedPercs = new ArrayList<>();
            List<Long> savedMillis = new ArrayList<>();
            Double prevPerc = null;
            for (int j = 1; j < originalPercs.size(); ++j) {
                Double perc = originalPercs.get(j);
                if (!perc.equals(prevPerc)) {
                    savedPercs.add(perc);
                    savedMillis.add(originalMillis.get(j));
                }
                prevPerc = perc;
            }
            double[] xvals = new double[savedPercs.size()];
            double[] yvals = new double[savedMillis.size()];
            for (int j = 0; j < savedPercs.size(); ++j) {
                xvals[j] = savedPercs.get(j);
                yvals[j] = savedMillis.get(j);
            }
            UnivariateFunction interp = INTERP.interpolate(xvals, yvals);
            double minX = xvals[0];
            double maxX = xvals[xvals.length - 1];
            for (int j = 0; j < PERCENTILE_POINTS.length; ++j) {
                double x = PERCENTILE_POINTS[j];
                double y;
                if (x < minX) {
                    y = yvals[0];
                } else if (x > maxX) {
                    y = yvals[yvals.length - 1];
                } else {
                    y = interp.value(x);
                }
                Double perc = x;
                Long millis = Math.round(y);
                List<Long> values = unified.get(perc);
                if (values == null) {
                    values = new ArrayList<>(percentiles.size());
                    for (int k = 0; k < percentiles.size(); ++k) {
                        values.add(null);
                    }
                    unified.put(perc, values);
                }
                values.set(i, millis);
            }
        }

        return unified;
    }

    private static Chart createPercentileChart(String name, String urlName, List<Percentiles> percentiles, List<String> seriesNames, int index) {
        if (percentiles == null) {
            LOGGER.debug("Percentiles list is empty.");
            return new Chart(name, urlName, "", null);
        }
        SortedMap<Double, List<Long>> unifiedPercs = unifyPercentiles(percentiles);
        StringBuilder sb = new StringBuilder();
        sb.append("new Dygraph(document.getElementById(\"graphdiv").append(index).append("\"),\n");
        String csvRowTail = " +\n";

        sb.append("\"percentile,");
        // THIS WON'T WORK WITH SERIES NAMES THAT HAVE COMMAS!
        for (String seriesName : seriesNames) {
            sb.append(seriesName).append(",");
        }
        if (!seriesNames.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        sb.append("\\n\"").append(csvRowTail);

        for (Entry<Double, List<Long>> percs : unifiedPercs.entrySet()) {
            Double perc = percs.getKey();
            List<Long> seriesMillis = percs.getValue();
            sb.append("\"").append(perc).append(",");
            for (Long millis : seriesMillis) {
                if (millis != null) {
                    sb.append(millis);
                }
                sb.append(",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("\\n\"").append(csvRowTail);
        }
        if (!unifiedPercs.isEmpty()) {
            sb.setLength(sb.length() - csvRowTail.length());
        }
        sb.append(", {\n");
        sb.append("legend: 'always',\n");
        sb.append("xlabel: 'Percentile',\n");
        sb.append("ylabel: 'Response Time (ms)',\n");
        sb.append("connectSeparatedPoints: true,\n");
        sb.append("});");
        return new Chart(name, urlName, sb.toString(), null);
    }

    private Metrics getMetrics(long logId, long labelId) {
        Stats stats = statsSrv.getAggregate(logId, labelId);
        Percentiles percentiles = statsSrv.getPercentiles(logId, labelId);
        return new Metrics(stats, percentiles);
    }

    private List<Chart> createReviewCharts(List<Log> briefs) {
        Map<String, List<Metrics>> metricsMap = new TreeMap<>();
        int iBrief = 0;
        for (Log brief : briefs) {
            List<String> labels = statsSrv.getSampleLabels(brief.getId());
            int iLabel = 0;
            for (String label : labels) {
                Metrics metrics = getMetrics(brief.getId(), iLabel);
                List<Metrics> labelMetrics = metricsMap.get(label);
                if (labelMetrics == null) {
                    labelMetrics = new ArrayList<>(briefs.size());
                    metricsMap.put(label, labelMetrics);
                }
                // All logs must maintain the same index in each label's list.
                if (labelMetrics.size() < iBrief) {
                    for (int i = 0; i < iBrief; ++i) {
                        labelMetrics.add(EMPTY_METRICS);
                    }
                }
                labelMetrics.add(metrics);
                ++iLabel;
            }
            ++iBrief;
        }
        // Make sure each list is the same length.
        for (List<Metrics> labelMetrics : metricsMap.values()) {
            for (int i = labelMetrics.size(); i < briefs.size(); ++i) {
                labelMetrics.add(EMPTY_METRICS);
            }
        }

        // Now, we have a map of key=categoryName, value=ordered list of values per log.
        // But, what we need is a map of key=logName, value=ordered list of values per category.
        List<String> categoryNames = new ArrayList<>(metricsMap.size());
        List<String> seriesNames = new ArrayList<>(briefs.size());
        List<List<Metrics>> seriesCategoriesMetrics = new ArrayList<>(briefs.size());
        for (Log brief : briefs) {
            seriesNames.add(brief.getName());
            List<Metrics> logValues = new ArrayList<>(metricsMap.size());
            seriesCategoriesMetrics.add(logValues);
        }
        for (Entry<String, List<Metrics>> statsEntry : metricsMap.entrySet()) {
            categoryNames.add(statsEntry.getKey());
            for (int i = 0; i < briefs.size(); ++i) {
                List<Metrics> logValues = seriesCategoriesMetrics.get(i);
                logValues.add(statsEntry.getValue().get(i));
            }
        }

        List<Chart> charts = new ArrayList<Chart>();
        charts.add(createStatBarChart("Average", "avg", categoryNames, seriesNames, seriesCategoriesMetrics, Stats::getAvg, false, 0));
        charts.add(createStatBarChart("Median", "p50", categoryNames, seriesNames, seriesCategoriesMetrics, Stats::getP50, false, 1));
        charts.add(createStatBarChart("90th Percentile", "p90", categoryNames, seriesNames, seriesCategoriesMetrics, Stats::getP90, false, 2));
        charts.add(createStatBarChart("95th Percentile", "p95", categoryNames, seriesNames, seriesCategoriesMetrics, Stats::getP95, false, 3));
        charts.add(createStatBarChart("99th Percentile", "p99", categoryNames, seriesNames, seriesCategoriesMetrics, Stats::getP99, false, 4));
        charts.add(createStatBarChart("Error Count", "numErrors", categoryNames, seriesNames, seriesCategoriesMetrics, Stats::getNumErrors, false, 5));
        charts.add(createStatBarChart("Error %", "percErrors", categoryNames, seriesNames, seriesCategoriesMetrics, (t) -> {
            if (t == null || t.getNumSamples() == 0) {
                return "0";
            }
            return PERC_FORMAT.get().format(((double) t.getNumErrors() * 100d) / ((double) t.getNumSamples()));
        }, true, 6));
        charts.addAll(createMultiPercentileChart(categoryNames, seriesNames, seriesCategoriesMetrics, 7));

        return charts;
    }

    private List<Chart> createMultiPercentileChart(List<String> categoryNames, List<String> seriesNames, List<List<Metrics>> seriesCategoriesMetrics, int index) {
        List<Chart> percentiles = new ArrayList<>(seriesNames.size() * categoryNames.size());
        for (int chartI = 0; chartI < categoryNames.size(); ++chartI) {
            List<Percentiles> seriesCategory = new ArrayList<>();
            for (List<Metrics> categories : seriesCategoriesMetrics) {
                seriesCategory.add(categories.get(chartI).percentiles());
            }
            String categoryName = categoryNames.get(chartI);
            String name = categoryName + " - Percentiles";
            String urlName = SLG.slugify(name);
            Chart chart = createPercentileChart(name, urlName, seriesCategory, seriesNames, index + chartI);
            percentiles.add(chart);
        }
        return percentiles;
    }

    private Chart createStatBarChart(String name, String urlName, List<String> categoryNames, List<String> seriesNames, List<List<Metrics>> seriesCategoriesMetrics, Function<Stats, ?> statPart, boolean isPercentage, int index) {
        List<List<?>> results = new ArrayList<>(seriesCategoriesMetrics.size());
        for (List<Metrics> listMetrics : seriesCategoriesMetrics) {
            List<Object> category = new ArrayList<>(listMetrics.size());
            for (Metrics metrics : listMetrics) {
                category.add(statPart.apply(metrics.stats()));
            }
            results.add(category);
        }
        return createBarChart(name, urlName, categoryNames, seriesNames, results, isPercentage, index);
    }

    private static Chart createBarChart(String name, String urlName, List<String> categoryNames, List<String> seriesNames,
            List<List<?>> seriesCategoriesValues, boolean isPercentage, int index) {
        StringBuilder sb = new StringBuilder();
        sb.append("new Chartist.Bar('#graphdiv").append(index).append("', {\n");
        sb.append("  labels: [");
        for (String categoryName : categoryNames) {
            sb.append("'").append(categoryName).append("',");
        }
        if (!categoryNames.isEmpty()) {
            // If we have at least one category listed, then take off the last comma
            sb.setLength(sb.length() - 1);
        }
        sb.append("],\n");
        sb.append("  series: [\n");
        for (int i = 0; i < seriesCategoriesValues.size(); ++i) {
            List<?> categoryValues = seriesCategoriesValues.get(i);
            String seriesName = seriesNames.get(i);
            sb.append("    {\"name\": \"")
                    .append(seriesName)
                    .append("\", \"data\": [");
            for (int j = 0; j < categoryValues.size(); ++j) {
                sb.append(categoryValues.get(j)).append(",");
            }
            if (!categoryValues.isEmpty()) {
                // If we have at least one item in the series, then take off the last comma
                sb.setLength(sb.length() - 1);
            }
            sb.append("]},\n");
        }

        if (!seriesCategoriesValues.isEmpty()) {
            // If there is at least one series in the map, then remove last comma.
            sb.setLength(sb.length() - 2);
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}, {\n");
        sb.append("  seriesBarDistance: 10,\n");
        sb.append("  horizontalBars: true,\n");
        sb.append("  reverseDate: true,\n");
        sb.append("  axisY: {\n");
        sb.append("    offset: 70\n");
        sb.append("  },\n");
        if (isPercentage) {
            sb.append("  axisX: {\n");
            sb.append("    type: Chartist.FixedScaleAxis,\n");
            sb.append("    ticks: [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100],\n");
            sb.append("    low: 0,\n");
            sb.append("    high: 100,\n");
            sb.append("    labelInterpolationFnc: function(value) {\n");
            sb.append("      return value + '%';\n");
            sb.append("    },\n");
            sb.append("  },\n");
        }
        sb.append("  plugins: [\n");
        sb.append("    Chartist.plugins.tooltip({\n");
        if (isPercentage) {
            sb.append("    transformTooltipTextFnc: function(value) {\n");
            sb.append("      return value + '%';\n");
            sb.append("    },\n");
        }
        sb.append("      anchorToPoint: false\n");
        sb.append("    }),\n");
        sb.append("    Chartist.plugins.legend({\n");
        sb.append("      position: 'top'\n");
        sb.append("    })\n");
        sb.append("  ]\n");
        sb.append("});\n");

        // Height calculation is based on number of series * number of categories, rather than
        // using the ct-square (or other sizes) since that causes the legend to disappear.
        int height = categoryNames.size() * seriesNames.size();
        height = Math.max(height, 10);
        String heightText = height + "em";

        return new Chart(name, urlName, sb.toString(), heightText);
    }

    private void calculateReviewLogs(Review review) {
        try {
            String body = review.getBody();
            LabelSelectorExpression labelSelector = LabelSelectorParser.parse(body);
            List<Long> logIds = logsSrv.listIdsBySelector(labelSelector);

            reviewsSrv.setReviewLogs(review.getId(), logIds);
        } catch (LabelSelectorSyntaxException ex) {
            LOGGER.error("Could not find logs for review due to Syntax error in label selector for review_id={}", review.getId());
        }
    }

    private static String commonMarkToHtml(String commonMarkText) {
        Node document = CM_PARSER.parse(commonMarkText);
        return HTML_RENDERER.render(document);

    }

    private static interface NameValueListener {

        void beginParse();

        void nameValue(String name, String value);

        void endParse();
    }

    private static class ContentDispositionSubParts implements NameValueListener {

        private String name;
        private String filename;

        public String getName() {
            return name;
        }

        public String getFilename() {
            return filename;
        }

        public void clear() {
            name = null;
            filename = null;
        }

        @Override
        public void beginParse() {
            clear();
        }

        @Override
        public void nameValue(String inName, String inValue) {
            switch (inName) {
                case "name":
                    name = inValue;
                    break;
                case "filename":
                    filename = inValue;
                    break;
                default:
                    // Skip it, we don't use it.
                    break;
            }
        }

        @Override
        public void endParse() {
            // Good.
        }

    }

    private static class Metrics {

        private final Stats stats;
        private final Percentiles percentiles;

        public Metrics(Stats inStats, Percentiles inPercentiles) {
            stats = inStats;
            percentiles = inPercentiles;
        }

        public Stats stats() {
            return stats;
        }

        public Percentiles percentiles() {
            return percentiles;
        }
    }

    private static class ConstantOrLinearInterpolator implements UnivariateInterpolator {

        private static final UnivariateInterpolator LINEINTERP = new LinearInterpolator();

        @Override
        public UnivariateFunction interpolate(double[] xvals, double[] yvals) throws MathIllegalArgumentException, DimensionMismatchException {
            if (xvals.length == 1 && yvals.length == 1) {
                // If we only have one value, then return it along the entire x-axis.
                return new ConstantFunction(yvals[0]);
            } else {
                return LINEINTERP.interpolate(xvals, yvals);
            }
        }
    }

    private static class ConstantFunction implements UnivariateFunction {

        private final double yval;

        public ConstantFunction(double inYval) {
            yval = inYval;
        }

        @Override
        public double value(double d) {
            return yval;
        }
    }

}
