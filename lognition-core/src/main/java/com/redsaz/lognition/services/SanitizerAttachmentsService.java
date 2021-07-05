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
import com.redsaz.lognition.api.model.Attachment;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class SanitizerAttachmentsService implements AttachmentsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SanitizerAttachmentsService.class);

    private final AttachmentsService srv;

    public SanitizerAttachmentsService(AttachmentsService attachmentsService) {
        srv = attachmentsService;
    }

    @Override
    public Attachment put(Attachment source, InputStream data) {
        return srv.put(source, data);
    }

    @Override
    public InputStream getData(String owner, String path) {
        return srv.getData(owner, path);
    }

    @Override
    public Attachment get(String owner, String path) {
        return srv.get(owner, path);
    }

    @Override
    public List<Attachment> listForOwner(String owner) {
        return srv.listForOwner(owner);
    }

    @Override
    public Attachment update(Attachment source) {
        return srv.update(source);
    }

    @Override
    public void delete(String owner, String path) {
        srv.delete(owner, path);
    }

    @Override
    public void deleteForOwner(String owner) {
        srv.deleteForOwner(owner);
    }

}
