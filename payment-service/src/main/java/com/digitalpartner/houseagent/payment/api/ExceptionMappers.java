package com.digitalpartner.houseagent.payment.api;

import com.digitalpartner.houseagent.common.api.ApiError;
import com.digitalpartner.houseagent.payment.service.DuplicatePaymentException;
import com.digitalpartner.houseagent.payment.service.IllegalPaymentStateException;
import com.digitalpartner.houseagent.payment.service.IllegalSettlementConfigException;
import com.digitalpartner.houseagent.payment.service.InvoiceNotFoundException;
import com.digitalpartner.houseagent.payment.service.PaymentNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExceptionMappers {

    @ServerExceptionMapper
    public Response invoiceNotFound(InvoiceNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("INVOICE_NOT_FOUND", e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response paymentNotFound(PaymentNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("PAYMENT_NOT_FOUND", e.getMessage()))
                .build();
    }

    /**
     * 409 with the id of the payment that already represents this transaction.
     *
     * <p>A provider retrying a callback needs to learn that the money is recorded, not
     * to be told it did something wrong. Returning the existing id lets it stop.
     */
    @ServerExceptionMapper
    public Response duplicatePayment(DuplicatePaymentException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("PAYMENT_ALREADY_RECORDED",
                        e.getMessage() + " as payment " + e.existingPaymentId()))
                .build();
    }

    /**
     * 409 rather than 400: the request was well-formed and might have been valid a
     * moment earlier - before the invoice was paid off by someone else, say. The
     * client's correct response is to re-read the invoice, not to fix its payload.
     */
    @ServerExceptionMapper
    public Response illegalPaymentState(IllegalPaymentStateException e) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("PAYMENT_STATE_CONFLICT", e.getMessage()))
                .build();
    }

    /** 400: a settlement policy that cannot mean anything is the caller's mistake. */
    @ServerExceptionMapper
    public Response illegalSettlementConfig(IllegalSettlementConfigException e) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("INVALID_SETTLEMENT_CONFIG", e.getMessage()))
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
