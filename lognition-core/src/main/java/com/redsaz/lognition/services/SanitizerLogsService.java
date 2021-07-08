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

import com.github.slugify.Slugify;
import com.redsaz.lognition.api.LogsService;
import com.redsaz.lognition.api.labelselector.LabelSelectorExpression;
import com.redsaz.lognition.api.model.Attachment;
import com.redsaz.lognition.api.model.Label;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Log.Status;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Pattern;
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
public class SanitizerLogsService implements LogsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SanitizerLogsService.class);

    private static final Slugify SLG = initSlug();
    private static final int SHORTENED_MAX = 60;
    private static final int SHORTENED_MIN = 12;
    private static final ThreadLocal<SimpleDateFormat> LONG_DATE
            = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf;
        }
    };

    private final LogsService srv;

    public SanitizerLogsService(LogsService logsService) {
        srv = logsService;
    }

    @Override
    public Log create(Log source) {
        return srv.create(sanitize(source));
    }

//    @Override
//    public List<LogBrief> getLogBriefs() {
//        return sanitizeAll(srv.getLogBriefs());
//    }
//
//    @Override
//    public LogBrief getLogBrief(long id) {
//        return sanitize(srv.getLogBrief(id));
//    }
    @Override
    public File getAvroFile(long id) throws FileNotFoundException {
        return srv.getAvroFile(id);
    }

    @Override
    public InputStream getCsvContent(long id) throws IOException {
        return srv.getCsvContent(id);
    }

//    @Override
//    public LogBrief createBrief(LogBrief source) {
//        if (source == null) {
//            throw new AppClientException("No log brief provided.");
//        }
//        return srv.createBrief(sanitize(source));
//    }
//
//    @Override
//    public Log createContent(InputStream raw) {
//        if (raw == null) {
//            throw new AppClientException("No log provided.");
//        }
//        return srv.createContent(raw);
//    }
    @Override
    public void delete(long id) {
        srv.delete(id);
    }

    @Override
    public Log get(long id) {
        return srv.get(id);
    }

    @Override
    public List<Log> list() {
        return srv.list();
    }

    @Override
    public List<Long> listIdsBySelector(LabelSelectorExpression labelSelector) {
        return srv.listIdsBySelector(labelSelector);
    }

    @Override
    public Log update(Log source) {
        // Don't use sanitize, as update CAN contain null fields. It means those weren't updated.
        if (source == null) {
            return null;
        }
        String uriName = source.getUriName();
        if (uriName != null) {
            uriName = SLG.slugify(uriName);
        }

        source = new Log(source.getId(), source.getStatus(), uriName, source.getName(), source.getDataFile(), source.getNotes());

        return srv.update(source);
    }

    @Override
    public void updateStatus(long id, Status newStatus) {
        srv.updateStatus(id, newStatus);
    }

    @Override
    public List<Label> setLabels(long logId, Collection<Label> labels) {
        return srv.setLabels(logId, sanitizeLabels(labels));
    }

    @Override
    public List<Label> getLabels(long logId) {
        return srv.getLabels(logId);
    }

    @Override
    public Attachment putAttachment(long logId, Attachment source, InputStream data) {
        return srv.putAttachment(logId, source, data);
    }

    @Override
    public Attachment updateAttachment(long logId, Attachment source) {
        return srv.updateAttachment(logId, source);
    }

    @Override
    public List<Attachment> listAttachments(long logId) {
        return srv.listAttachments(logId);
    }

    @Override
    public InputStream getAttachmentData(long logId, String attachmentPath) {
        return srv.getAttachmentData(logId, attachmentPath);
    }

    @Override
    public void deleteAttachment(long logId, String attachmentPath) {
        srv.deleteAttachment(logId, attachmentPath);
    }

    /**
     * Ensures nothing is null. The ID will remain unchanged.
     *
     * @param source The log to sanitize
     * @return A new brief instance with sanitized data.
     */
    private static Log sanitize(Log source) {
        if (source == null) {
            source = Log.emptyLog();
        }
        Status status = source.getStatus();
        if (status == null) {
            status = Status.UNSPECIFIED;
        }
        String uriName = source.getUriName();
        if (uriName == null || uriName.isEmpty()) {
            uriName = source.getName();
        }
        uriName = SLG.slugify(uriName);

        String title = source.getName();
        if (title == null) {
            title = shortened(source.getNotes());
            if (title == null) {
                title = "";
            }
        }
        String notes = source.getNotes();
        if (notes == null) {
            notes = "";
        }

        return new Log(source.getId(), source.getStatus(), uriName, title, source.getDataFile(), notes);
    }

    private static String shortened(String text) {
        if (text == null || text.length() <= SHORTENED_MAX) {
            return text;
        }
        text = text.substring(0, SHORTENED_MAX);
        String candidate = text.replaceFirst("\\S+$", "");
        if (candidate.length() < SHORTENED_MIN) {
            candidate = text;
        }

        return candidate + "...";
    }

    private static Slugify initSlug() {
        return new Slugify();
    }

    private static List<Label> sanitizeLabels(Collection<Label> labels) {
        if (labels == null) {
            return Collections.emptyList();
        }
        Map<String, Label> sanitized = new TreeMap<String, Label>();
        for (Label label : labels) {
            label = sanitizeLabel(label);
            if (label == null) {
                continue;
            }
            sanitized.putIfAbsent(label.getKey(), label);
        }
        return new ArrayList<Label>(sanitized.values());
    }

    public static void main(String[] args) {
        System.out.println(sanitizeLabel(null));
        System.out.println(sanitizeLabel(new Label(null, null)));
        System.out.println(sanitizeLabel(new Label(" ", null)));
        System.out.println(sanitizeLabel(new Label("a", null)));
        System.out.println(sanitizeLabel(new Label("a.", null)));
        System.out.println(sanitizeLabel(new Label("a.b", null)));
        System.out.println(sanitizeLabel(new Label("a-", null)));
        System.out.println(sanitizeLabel(new Label("a-b", null)));
        System.out.println(sanitizeLabel(new Label("a H", null)));
        System.out.println(sanitizeLabel(new Label("1", null)));
        System.out.println(sanitizeLabel(new Label("a", "")));
        System.out.println(sanitizeLabel(new Label("a", " ")));
        System.out.println(sanitizeLabel(new Label("a", "a")));
        System.out.println(sanitizeLabel(new Label("a", "a.")));
        System.out.println(sanitizeLabel(new Label("a", "a.b")));
        System.out.println(sanitizeLabel(new Label("a", "a-")));
        System.out.println(sanitizeLabel(new Label("a", "a-b")));
        System.out.println(sanitizeLabel(new Label("a", "a H")));
        System.out.println(sanitizeLabel(new Label("a", "1")));
        System.out.println(sanitizeLabel(new Label("abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabc", null)));
        System.out.println(sanitizeLabel(new Label("a", "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabc")));
        System.out.println(sanitizeLabel(new Label("abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcd", null)));
        System.out.println(sanitizeLabel(new Label("a", "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcd")));
    }
    private static final Pattern labelPattern = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9.\\-]*");
    private static final Pattern labelEndPattern = Pattern.compile(".*[a-zA-Z0-9]$");

    private static Label sanitizeLabel(Label label) {
        if (label == null || label.getKey() == null) {
            return null;
        }
        if (labelPattern.matcher(label.getKey()).matches()
                && labelEndPattern.matcher(label.getKey()).matches()
                && label.getKey().length() < 64) {
            if (label.getValue() == null) {
                return new Label(label.getKey(), "");
            } else if (label.getValue().isEmpty()
                    || (labelPattern.matcher(label.getValue()).matches()
                    && labelEndPattern.matcher(label.getValue()).matches()
                    && label.getValue().length() < 64)) {
                return label;
            }
        }
        return null;
    }
}
