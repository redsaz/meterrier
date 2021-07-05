/**
 * Copyright 2021 Redsaz <redsaz@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.redsaz.lognition.api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.beans.ConstructorProperties;
import java.util.Objects;

/**
 * Details on an uploaded file.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
public class Attachment {

    private final long id;
    private final String owner;
    private final String path;
    private final String description;
    private final String mimeType;
    private final long uploadedUtcMillis;

    @JsonCreator
    @ConstructorProperties({"id", "owner", "path", "description", "mimetype", "uploadedUtcMillis"})
    public Attachment(
            @JsonProperty("id") long id,
            @JsonProperty("owner") String owner,
            @JsonProperty("path") String path,
            @JsonProperty("description") String description,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("uploadedUtcMillis") long uploadedUtcMillis) {
        this.id = id;
        this.owner = owner;
        this.path = path;
        this.description = description;
        this.mimeType = mimeType;
        this.uploadedUtcMillis = uploadedUtcMillis;
    }

    public long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getPath() {
        return path;
    }

    public String getDescription() {
        return description;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getUploadedUtcMillis() {
        return uploadedUtcMillis;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 79 * hash + (int) (this.id ^ (this.id >>> 32));
        hash = 79 * hash + Objects.hashCode(this.owner);
        hash = 79 * hash + Objects.hashCode(this.path);
        hash = 79 * hash + Objects.hashCode(this.description);
        hash = 79 * hash + Objects.hashCode(this.mimeType);
        hash = 79 * hash + (int) (this.uploadedUtcMillis ^ (this.uploadedUtcMillis >>> 32));
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Attachment other = (Attachment) obj;
        if (this.id != other.id) {
            return false;
        }
        if (this.uploadedUtcMillis != other.uploadedUtcMillis) {
            return false;
        }
        if (!Objects.equals(this.owner, other.owner)) {
            return false;
        }
        if (!Objects.equals(this.path, other.path)) {
            return false;
        }
        if (!Objects.equals(this.mimeType, other.mimeType)) {
            return false;
        }
        if (!Objects.equals(this.description, other.description)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "Attachment{" + "id=" + id
                + ", owner=" + owner
                + ", pathname=" + path
                + ", description=" + description
                + ", mimeType=" + mimeType
                + ", uploadedUtcMillis=" + uploadedUtcMillis + '}';
    }

}
