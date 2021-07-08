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

import com.redsaz.lognition.api.labelselector.LabelSelectorExpression;
import com.redsaz.lognition.api.model.Attachment;
import com.redsaz.lognition.api.model.Label;
import com.redsaz.lognition.api.model.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * Stores and accesses logs/measurements.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public interface LogsService {

    Log create(Log source);

    InputStream getCsvContent(long id) throws IOException;

    File getAvroFile(long id) throws FileNotFoundException;

    Log get(long id);

    List<Log> list();

    List<Long> listIdsBySelector(LabelSelectorExpression labelSelector);

    Log update(Log source);

    void updateStatus(long id, Log.Status newStatus);

    void delete(long id);

    List<Label> setLabels(long logId, Collection<Label> labels);

    List<Label> getLabels(long logId);

    /**
     * Adds a new attachment or replaces an existing attachment. If the logId+attachment.path combo
     * already exist, then the attachment will be replaced.
     *
     * @param logId the owner of the attachment
     * @param source details of the attachment
     * @param data the attachment contents
     * @return The resulting Attachment data.
     */
    Attachment putAttachment(long logId, Attachment source, InputStream data);

    /**
     * Updates any details of the attachment (path, description, etc, but not data).
     *
     * @param logId the owner of the attachment
     * @param source the updated details of the attachment
     * @return The resulting Attachment data.
     */
    Attachment updateAttachment(long logId, Attachment source);

    /**
     * List all attachments for a single log.
     *
     * @param logId the owner of the attachments
     * @return a list of attachments for the review, or an empty list if none exist.
     */
    List<Attachment> listAttachments(long logId);

    InputStream getAttachmentData(long logId, String attachmentPath);

    void deleteAttachment(long logId, String attachmentPath);

}
