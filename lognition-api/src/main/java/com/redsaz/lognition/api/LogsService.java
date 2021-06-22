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

    public Log create(Log source);

    public InputStream getCsvContent(long id) throws IOException;

    public File getAvroFile(long id) throws FileNotFoundException;

    public Log get(long id);

    public List<Log> list();

    public List<Long> listIdsBySelector(LabelSelectorExpression labelSelector);

    public Log update(Log source);

    public void updateStatus(long id, Log.Status newStatus);

    public void delete(long id);

    public List<Label> setLabels(long logId, Collection<Label> labels);

    public List<Label> getLabels(long logId);

}
