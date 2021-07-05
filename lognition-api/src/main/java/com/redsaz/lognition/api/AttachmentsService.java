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
package com.redsaz.lognition.api;

import com.redsaz.lognition.api.model.Attachment;
import java.io.InputStream;
import java.util.List;

/**
 * Stores and accesses attachments.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public interface AttachmentsService {

    /**
     * Adds or replaces an attachment. If the attachment's owner and path already exist then the
     * attachment will be replaced with the new attachment. Otherwise the attachment will be added.
     *
     * @param source the original details of the attachment
     * @param data the content of the attachment to store
     * @return details of the new or updated attachment.
     */
    Attachment put(Attachment source, InputStream data);

    InputStream getData(String owner, String path);

    Attachment get(String owner, String path);
//    public Attachment get(long id);
//    public List<Attachment> getMany(Collection<Long> ids);

    List<Attachment> listForOwner(String owner);

//    public List<Attachment> listAll();
    /**
     * Updates the description and mimetype of the attachment.
     *
     * @param source contains the owner, path, and new description and/or mimetype. Null description
     * and mimetypes are not updated.
     * @return The result of the updates.
     */
    Attachment update(Attachment source);

    void delete(String owner, String path);

    void deleteForOwner(String owner);
//    void delete(long id);
//    void deleteMany(Collection<Long> ids);
}
