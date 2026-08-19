package com.digitalpartner.houseagent.property.api;

import com.digitalpartner.houseagent.common.api.ApiError;
import com.digitalpartner.houseagent.property.images.ForeignAssetException;
import com.digitalpartner.houseagent.property.images.ImageNotFoundException;
import com.digitalpartner.houseagent.property.images.ImagesNotConfiguredException;
import com.digitalpartner.houseagent.property.service.HouseNotFoundException;
import com.digitalpartner.houseagent.property.service.IllegalHouseStateException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns domain exceptions into the shared {@link ApiError} shape, so every service in
 * the platform fails the same way from a client's point of view.
 */
public class ExceptionMappers {

    @ServerExceptionMapper
    public Response houseNotFound(HouseNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("HOUSE_NOT_FOUND", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response illegalState(IllegalHouseStateException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("HOUSE_STATE_CONFLICT", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response imageNotFound(ImageNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("IMAGE_NOT_FOUND", e.getMessage()))
                .build();
    }

    /**
     * 403 rather than 404: unlike a foreign house, the caller is not probing for
     * something that might exist. They have named an asset outside their own path, and
     * saying so plainly is more useful than pretending it is missing.
     */
    @ServerExceptionMapper
    public Response foreignAsset(ForeignAssetException e) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(ApiError.of("FOREIGN_ASSET", e.getMessage()))
                .build();
    }

    /**
     * 503 rather than 500: nothing is broken and the request was valid. This deployment
     * simply has no image host, and a client that retries after one is configured will
     * succeed unchanged.
     */
    @ServerExceptionMapper
    public Response imagesNotConfigured(ImagesNotConfiguredException e) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(ApiError.of("IMAGES_NOT_CONFIGURED", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            // Strip the "method.arg0." prefix Bean Validation adds for method
            // parameters so clients see "address.city" rather than internals.
            String path = violation.getPropertyPath().toString();
            int lastDot = path.indexOf('.', path.indexOf('.') + 1);
            fields.put(lastDot > 0 ? path.substring(lastDot + 1) : path, violation.getMessage());
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.validation(fields))
                .build();
    }
}
