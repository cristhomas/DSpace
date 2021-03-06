/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.dspace.app.rest.model.BitstreamRest;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.MultipartFileSender;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.disseminate.service.CitationDocumentService;
import org.dspace.services.EventService;
import org.dspace.usage.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * This is a specialized controller to provide access to the bitstream binary
 * content
 *
 * The mapping for requested endpoint try to resolve a valid UUID, for example
 * <pre>
 * {@code
 * https://<dspace.url>/dspace-spring-rest/api/core/bitstreams/26453b4d-e513-44e8-8d5b-395f62972eff/content
 * }
 * </pre>
 *
 * @author Andrea Bollini (andrea.bollini at 4science.it)
 * @author Tom Desair (tom dot desair at atmire dot com)
 * @author Frederic Van Reet (frederic dot vanreet at atmire dot com)
 */
@RestController
@RequestMapping("/api/" + BitstreamRest.CATEGORY + "/" + BitstreamRest.PLURAL_NAME
    + "/{uuid:[0-9a-fxA-FX]{8}-[0-9a-fxA-FX]{4}-[0-9a-fxA-FX]{4}-[0-9a-fxA-FX]{4}-[0-9a-fxA-FX]{12}}/content")
public class BitstreamContentRestController {

    private static final Logger log = Logger.getLogger(BitstreamContentRestController.class);

    //Most file systems are configured to use block sizes of 4096 or 8192 and our buffer should be a multiple of that.
    private static final int BUFFER_SIZE = 4096 * 10;

    @Autowired
    private BitstreamService bitstreamService;

    @Autowired
    private EventService eventService;

    @Autowired
    private CitationDocumentService citationDocumentService;

    @PreAuthorize("hasPermission(#uuid, 'BITSTREAM', 'READ')")
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD})
    public void retrieve(@PathVariable UUID uuid, HttpServletResponse response,
                         HttpServletRequest request) throws IOException, SQLException, AuthorizeException {


        Context context = ContextUtil.obtainContext(request);

        Bitstream bit = bitstreamService.find(context, uuid);
        if (bit == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Long lastModified = bitstreamService.getLastModified(bit);
        BitstreamFormat format = bit.getFormat(context);
        String mimetype = format.getMIMEType();
        String name = getBitstreamName(bit, format);

        Pair<InputStream, Long> bitstreamTuple = getBitstreamInputStreamAndSize(context, bit);

        // Pipe the bits
        try (InputStream is = bitstreamTuple.getLeft()) {
            MultipartFileSender sender = MultipartFileSender
                    .fromInputStream(is)
                    .withBufferSize(BUFFER_SIZE)
                    .withFileName(name)
                    .withLength(bitstreamTuple.getRight())
                    .withChecksum(bit.getChecksum())
                    .withMimetype(mimetype)
                    .withLastModified(lastModified)
                    .with(request)
                    .with(response);

            if (sender.isNoRangeRequest() && isNotAnErrorResponse(response)) {
                //We only log a download request when serving a request without Range header. This is because
                //a browser always sends a regular request first to check for Range support.
                eventService.fireEvent(
                        new UsageEvent(
                                UsageEvent.Action.VIEW,
                                request,
                                context,
                                bit));
            }

            //We have all the data we need, close the connection to the database so that it doesn't stay open during
            //download/streaming
            context.complete();

            //Send the data
            if (sender.isValid()) {
                sender.serveResource();
            }

        } catch (ClientAbortException ex) {
            log.debug("Client aborted the request before the download was completed. " +
                          "Client is probably switching to a Range request.", ex);
        }
    }

    private Pair<InputStream, Long> getBitstreamInputStreamAndSize(Context context, Bitstream bit)
        throws SQLException, IOException, AuthorizeException {

        if (citationDocumentService.isCitationEnabledForBitstream(bit, context)) {
            return generateBitstreamWithCitation(context, bit);
        } else {
            return Pair.of(bitstreamService.retrieve(context, bit),bit.getSizeBytes());
        }
    }

    private Pair<InputStream, Long> generateBitstreamWithCitation(Context context, Bitstream bitstream)
        throws SQLException, IOException, AuthorizeException {
        //Create the cited document
        Pair<InputStream, Long> citationDocument = citationDocumentService.makeCitedDocument(context, bitstream);
        if (citationDocument.getLeft() == null) {
            log.error("CitedDocument was null");
        } else {
            if (log.isDebugEnabled()) {
                log.debug("CitedDocument was ok, has size " + citationDocument.getRight());
            }
        }
        return citationDocument;
    }

    private String getBitstreamName(Bitstream bit, BitstreamFormat format) {
        String name = bit.getName();
        if (name == null) {
            // give a default name to the file based on the UUID and the primary extension of the format
            name = bit.getID().toString();
            if (format != null && format.getExtensions() != null && format.getExtensions().size() > 0) {
                name += "." + format.getExtensions().get(0);
            }
        }
        return name;
    }

    private boolean isNotAnErrorResponse(HttpServletResponse response) {
        Response.Status.Family responseCode = Response.Status.Family.familyOf(response.getStatus());
        return responseCode.equals(Response.Status.Family.SUCCESSFUL)
            || responseCode.equals(Response.Status.Family.REDIRECTION);
    }
}
