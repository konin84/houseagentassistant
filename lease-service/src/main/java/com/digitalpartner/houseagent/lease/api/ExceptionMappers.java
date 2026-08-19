package com.digitalpartner.houseagent.lease.api;

import com.digitalpartner.houseagent.common.api.ApiError;
import com.digitalpartner.houseagent.lease.service.HouseAlreadyLetException;
import com.digitalpartner.houseagent.lease.service.IllegalLeaseStateException;
import com.digitalpartner.houseagent.lease.service.LeaseNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExceptionMappers {

    @ServerExceptionMapper
    public Response leaseNotFound(LeaseNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("LEASE_NOT_FOUND", e.getMessage()))
                .build();
    }

    /**
     * 409 rather than 400: the request was well-formed and would have been valid a
     * moment earlier. The client's correct response is to re-check availability, not
     * to fix its payload.
     */
    @ServerExceptionMapper
    public Response houseAlreadyLet(HouseAlreadyLetException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("HOUSE_ALREADY_LET", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response illegalState(IllegalLeaseStateException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("LEASE_STATE_CONFLICT", e.getMessage()))
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
