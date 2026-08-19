package com.digitalpartner.houseagent.property.images;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.property.images.ImageDtos.ImageResponse;
import com.digitalpartner.houseagent.property.images.ImageDtos.RegisterImageRequest;
import com.digitalpartner.houseagent.property.images.ImageDtos.UploadTicketResponse;
import com.digitalpartner.houseagent.property.security.CallerContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Photographs of a house.
 *
 * <h2>The upload flow</h2>
 *
 * <ol>
 *   <li>{@code POST .../images/upload-ticket} - this service signs one upload, bound to
 *       one path under the caller's own agency and house.</li>
 *   <li>The browser POSTs the file and the returned params straight to Cloudinary. The
 *       bytes never touch this service.</li>
 *   <li>{@code POST .../images} - the client echoes back the public id Cloudinary
 *       returned, and it is checked against the path it was signed for.</li>
 * </ol>
 *
 * <p>Two round trips rather than one, in exchange for never buffering a photograph in a
 * service that would otherwise be stateless.
 */
@Path("/api/agency/houses/{houseId}/images")
@Tag(name = "House images", description = "Photographs, hosted on Cloudinary")
@RolesAllowed({Roles.AGENT, Roles.AGENCY_ADMIN})
@Produces(MediaType.APPLICATION_JSON)
public class AgencyHouseImageResource {

    @Inject
    HouseImageService images;

    @Inject
    CallerContext caller;

    /**
     * Authorises one upload.
     *
     * <p>404 if the house belongs to another agency - the same 404 as for a house that
     * does not exist, so a signature request cannot be used to probe for other agencies'
     * house ids.
     */
    @POST
    @Path("/upload-ticket")
    public UploadTicketResponse requestUpload(@PathParam("houseId") UUID houseId) {
        caller.requireAgencyId();
        return images.requestUpload(houseId);
    }

    /** Records an asset the browser has already uploaded. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@PathParam("houseId") UUID houseId,
                             @Valid RegisterImageRequest request) {
        caller.requireAgencyId();
        ImageResponse registered = images.register(houseId, request);
        return Response.status(Response.Status.CREATED).entity(registered).build();
    }

    @GET
    public List<ImageResponse> list(@PathParam("houseId") UUID houseId) {
        caller.requireAgencyId();
        return images.list(houseId);
    }

    /** Makes this the marketplace thumbnail, replacing whichever image held it. */
    @PUT
    @Path("/{imageId}/cover")
    public ImageResponse makeCover(@PathParam("houseId") UUID houseId,
                                   @PathParam("imageId") UUID imageId) {
        caller.requireAgencyId();
        return images.makeCover(houseId, imageId);
    }

    /**
     * Removes the photograph, and destroys it at Cloudinary once the change has
     * committed. If it was the cover, the next image takes its place.
     */
    @DELETE
    @Path("/{imageId}")
    public Response delete(@PathParam("houseId") UUID houseId,
                           @PathParam("imageId") UUID imageId) {
        caller.requireAgencyId();
        images.delete(houseId, imageId);
        return Response.noContent().build();
    }
}
