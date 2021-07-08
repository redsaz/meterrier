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
package com.redsaz.lognition.api;

import com.redsaz.lognition.api.model.Attachment;
import com.redsaz.lognition.api.model.Log;
import com.redsaz.lognition.api.model.Review;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * Stores and accesses {@link Review}s.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public interface ReviewsService {

    Review create(Review source);

    Review get(long id);

    List<Review> list();

    Review update(Review source);

    void delete(long id);

    void setReviewLogs(long reviewId, Collection<Long> logIds);

    List<Log> getReviewLogs(long reviewId);

    /**
     * Adds a new attachment or replaces an existing attachment. If the reviewId+attachment.path
     * combo already exist, then the attachment will be replaced.
     *
     * @param reviewId the owner of the attachment
     * @param source details of the attachment
     * @param data the attachment contents
     * @return The resulting Attachment data.
     */
    Attachment putAttachment(long reviewId, Attachment source, InputStream data);

    /**
     * Updates any details of the attachment (path, description, etc, but not data).
     *
     * @param reviewId the owner of the attachment
     * @param source the updated details of the attachment
     * @return The resulting Attachment data.
     */
    Attachment updateAttachment(long reviewId, Attachment source);

    /**
     * List all attachments for a single review.
     *
     * @param reviewId the owner of the attachments
     * @return a list of attachments for the review, or an empty list if none exist.
     */
    List<Attachment> listAttachments(long reviewId);

    InputStream getAttachmentData(long reviewId, String attachmentPath);

    void deleteAttachment(long reviewId, String attachmentPath);
}
