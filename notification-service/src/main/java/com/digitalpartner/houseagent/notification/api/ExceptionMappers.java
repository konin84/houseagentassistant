package com.digitalpartner.houseagent.notification.api;

import com.digitalpartner.houseagent.common.api.ApiError;
import com.digitalpartner.houseagent.notification.service.IllegalContactException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExceptionMappers {

    @ServerExceptionMapper
    public Response notFound(NotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("CONTACT_NOT_FOUND", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response illegalContact(IllegalContactException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("INVALID_CONTACT", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response validation(ConstraintViolationException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            int lastDot = path.indexOf('.', path.indexOf('.') + 1);
            fields.put(lastDot > 0 ? path.substring(lastDot + 1) : path, violation.getMessage());
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.validation(fields))
                .build();
    }
}
