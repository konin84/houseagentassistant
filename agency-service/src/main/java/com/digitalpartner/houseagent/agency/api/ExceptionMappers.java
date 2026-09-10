package com.digitalpartner.houseagent.agency.api;

import com.digitalpartner.houseagent.agency.identity.DirectoryException;
import com.digitalpartner.houseagent.agency.identity.InvalidPhoneNumberException;
import com.digitalpartner.houseagent.agency.service.AgencyAlreadyExistsException;
import com.digitalpartner.houseagent.agency.service.AgencyNotFoundException;
import com.digitalpartner.houseagent.agency.service.AlreadyRegisteredException;
import com.digitalpartner.houseagent.agency.service.RoleNotGrantableException;
import com.digitalpartner.houseagent.agency.service.SignupFailedException;
import com.digitalpartner.houseagent.agency.service.StaffNotFoundException;
import com.digitalpartner.houseagent.common.api.ApiError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExceptionMappers {

    @ServerExceptionMapper
    public Response agencyNotFound(AgencyNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("AGENCY_NOT_FOUND", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response staffNotFound(StaffNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("STAFF_NOT_FOUND", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response agencyExists(AgencyAlreadyExistsException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("AGENCY_ALREADY_EXISTS", e.getMessage()))
                .build();
    }

    /**
     * 409 rather than 400: the request was well formed, and the caller could not have
     * known. Their next step is to look the person up, not to fix their payload.
     */
    @ServerExceptionMapper
    public Response alreadyRegistered(AlreadyRegisteredException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("EMAIL_ALREADY_REGISTERED", e.getMessage()))
                .build();
    }

    /**
     * 403 rather than 400. The caller is asking for something they are not entitled to
     * hand out - an agency admin minting another admin - and that is an authorisation
     * answer, not a malformed request.
     */
    @ServerExceptionMapper
    public Response roleNotGrantable(RoleNotGrantableException e) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(ApiError.of("ROLE_NOT_GRANTABLE", e.getMessage()))
                .build();
    }

    /**
     * 502: this service is fine, the identity provider is not. Distinguishing it from a
     * 500 matters, because the fix is somewhere else entirely.
     */
    @ServerExceptionMapper
    public Response directoryFailed(DirectoryException e) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(ApiError.of("IDENTITY_PROVIDER_ERROR", e.getMessage()))
                .build();
    }

    /**
     * 503 rather than 500. Signup ran out of ids to try, which says the platform is in
     * an odd state rather than that the caller sent anything wrong - and unlike a 500,
     * it tells them that trying again later is a reasonable thing to do.
     */
    @ServerExceptionMapper
    public Response signupFailed(SignupFailedException e) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(ApiError.of("SIGNUP_FAILED", e.getMessage()))
                .build();
    }

    /**
     * 400: the number cannot be dialled, so it cannot be signed in with either.
     *
     * <p>Its own code rather than VALIDATION_FAILED, because the check happens after
     * normalisation - '07-00-00-00-00' is fine and '0700' is not, which no pattern over
     * the raw input expresses. A client should show it against the phone field.
     */
    @ServerExceptionMapper
    public Response invalidPhone(InvalidPhoneNumberException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("INVALID_PHONE_NUMBER", e.getMessage()))
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
