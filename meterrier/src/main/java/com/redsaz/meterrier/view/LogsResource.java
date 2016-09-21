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
package com.redsaz.meterrier.view;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import com.redsaz.meterrier.api.MeterrierMediaType;
import com.redsaz.meterrier.api.LogsService;
import com.redsaz.meterrier.api.model.Log;
import java.io.InputStream;

/**
 * An endpoint for accessing log. Many of the REST endpoints and browser
 * endpoints are identical where possible; look at docs/endpoints.md for why.
 *
 * @author Redsaz <redsaz@gmail.com>
 */
@Path("/logs")
public class LogsResource {

    private LogsService logsSrv;

    public LogsResource() {
    }

    @Inject
    public LogsResource(@Sanitizer LogsService logsService) {
        logsSrv = logsService;
    }

    /**
     * Lists all of the logs URI and titles.
     *
     * @return Logs, by URI and title.
     */
    @GET
    @Produces(MeterrierMediaType.LOGBRIEFS_V1_JSON)
    public Response listLogBriefs() {
        return Response.ok(logsSrv.getLogs()).build();
    }

    /**
     * Get the note contents.
     *
     * @param id The id of the note.
     * @return Note.
     */
    @GET
    @Produces({MeterrierMediaType.LOGBRIEF_V1_JSON})
    @Path("{id}")
    public Response getLogBrief(@PathParam("id") long id) {
        Log brief = logsSrv.getLog(id);
        if (brief == null) {
            throw new NotFoundException("Could not find log brief id=" + id);
        }
        return Response.ok(brief).build();
    }

    @POST
    @Consumes("application/octet-stream")
    @Produces({MeterrierMediaType.LOGBRIEF_V1_JSON})
    public Response createLog(InputStream source) {
        return Response.status(Status.CREATED).entity(logsSrv.createLog(source)).build();
    }

    @DELETE
    @Path("{id}")
    public Response deleteLog(@PathParam("id") long id) {
        logsSrv.deleteLog(id);
        return Response.status(Status.NO_CONTENT).build();
    }

}