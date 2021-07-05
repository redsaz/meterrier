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
package com.redsaz.lognition.services;

import com.github.slugify.Slugify;
import com.redsaz.lognition.api.ReviewsService;
import com.redsaz.lognition.api.model.Attachment;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Review;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does not directly store reviews, but is responsible for ensuring that the reviews and metadata
 * sent to and retrieved from the store are correctly formatted, sized, and without
 * malicious/errorific content.
 *
 * Default values for jtl files:
 * timestamp,elapsed,label,responseCode,responseCode,responseMessage,threadName,dataType,success,bytes,grpThreads,allThreads,Latency
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class SanitizerReviewsService implements ReviewsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SanitizerReviewsService.class);

    private static final Slugify SLG = initSlug();
    private static final int SHORTENED_MAX = 60;
    private static final int SHORTENED_MIN = 12;

    private final ReviewsService srv;

    public SanitizerReviewsService(ReviewsService reviewsService) {
        srv = reviewsService;
    }

    @Override
    public Review create(Review source) {
        return srv.create(sanitize(source));
    }

    @Override
    public void delete(long id) {
        srv.delete(id);
    }

    @Override
    public Review get(long id) {
        return srv.get(id);
    }

    @Override
    public List<Review> list() {
        return srv.list();
    }

    @Override
    public Review update(Review source) {
        // Don't use sanitize, as update CAN contain null fields. It means those weren't updated.
        if (source == null) {
            return null;
        }
        String uriName = source.getUriName();
        if (uriName != null) {
            uriName = SLG.slugify(uriName);
        }

        source = new Review(source.getId(), uriName, source.getName(), source.getDescription(),
                source.getCreatedMillis(), source.getLastUpdatedMillis(), source.getBody());
        return srv.update(source);
    }

    @Override
    public void setReviewLogs(long reviewId, Collection<Long> logIds) {
        srv.setReviewLogs(reviewId, logIds);
    }

    @Override
    public List<Log> getReviewLogs(long reviewId) {
        return srv.getReviewLogs(reviewId);
    }

    @Override
    public Attachment putAttachment(long reviewId, Attachment source, InputStream data) {
        return srv.putAttachment(reviewId, source, data);
    }

    @Override
    public List<Attachment> listAttachments(long reviewId) {
        return srv.listAttachments(reviewId);
    }

    @Override
    public InputStream getAttachmentData(long reviewId, String attachmentPath) {
        return srv.getAttachmentData(reviewId, attachmentPath);
    }

    @Override
    public void deleteAttachment(long reviewId, String attachmentPath) {
        srv.deleteAttachment(reviewId, attachmentPath);
    }

    /**
     * Ensures nothing is null. The ID will remain unchanged.
     *
     * @param source The review to sanitize
     * @return A new brief instance with sanitized data.
     */
    private static Review sanitize(Review source) {
        if (source == null) {
            source = new Review(0, null, null, null,
                    ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond(), null, null);
        }
        String uriName = source.getUriName();
        if (uriName == null || uriName.isEmpty()) {
            uriName = source.getName();
        }
        uriName = SLG.slugify(uriName);

        String name = source.getName();
        if (name == null) {
            // If the name is null, see if we can use the notes instead.
            name = shortened(source.getDescription());
            if (name == null) {
                name = "";
            }
        }
        String description = source.getDescription();
        if (description == null) {
            description = "";
        }
        String body = source.getBody();
        if (body == null) {
            body = "";
        }

        return new Review(source.getId(), uriName, name, description, source.getCreatedMillis(),
                source.getLastUpdatedMillis(), body);
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

}
