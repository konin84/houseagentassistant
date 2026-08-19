package com.digitalpartner.houseagent.payment.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.api.dto.PageResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.ConfirmSettlementRequest;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.FailPaymentRequest;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.PaymentResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.RecordPaymentRequest;
import com.digitalpartner.houseagent.payment.security.CallerContext;
import com.digitalpartner.houseagent.payment.service.AgencyBilling;
import com.digitalpartner.houseagent.payment.service.PaymentService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Recording rent for agency staff.
 *
 * <p>Two ways in, because rent arrives two ways. Cash over the counter is recorded
 * already settled - there is nothing to confirm. A mobile money push is initiated by
 * the renter and confirmed here when the provider says so.
 */
@Path("/api/agency/payments")
@Tag(name = "Agency payments", description = "Recording and confirming rent")
@RolesAllowed({Roles.AGENT, Roles.AGENCY_ADMIN})
@Produces(MediaType.APPLICATION_JSON)
public class AgencyPaymentResource {

    @Inject
    PaymentService payments;

    @Inject
    AgencyBilling billing;

    @Inject
    CallerContext caller;

    /** Records money the agency already holds. Returns 409 if it would overpay. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@Valid RecordPaymentRequest request) {
        caller.requireAgencyId();
        PaymentResponse recorded = payments.recordSettled(request);
        return Response.created(URI.create("/api/agency/payments/" + recorded.id()))
                .entity(recorded)
                .build();
    }

    @GET
    public PageResponse<PaymentResponse> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        caller.requireAgencyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), AgencyBilling.MAX_PAGE_SIZE);

        List<PaymentResponse> items = billing.payments(safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, billing.countPayments());
    }

    @GET
    @Path("/{id}")
    public PaymentResponse get(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return payments.get(id);
    }

    /**
     * Confirms that a pending payment has settled with the provider.
     *
     * <p>Deliberately safe to call twice: a provider that redelivers a confirmation
     * gets the payment it already settled rather than an error it will keep retrying,
     * and the invoice is credited once.
     */
    @POST
    @Path("/{id}/settlement")
    @Consumes(MediaType.APPLICATION_JSON)
    public PaymentResponse confirm(@PathParam("id") UUID id, ConfirmSettlementRequest request) {
        caller.requireAgencyId();
        return payments.confirmSettlement(id, request == null ? null : request.providerReference());
    }

    /** Records that the provider rejected the payment. The invoice stays owed. */
    @POST
    @Path("/{id}/failure")
    @Consumes(MediaType.APPLICATION_JSON)
    public PaymentResponse fail(@PathParam("id") UUID id, FailPaymentRequest request) {
        caller.requireAgencyId();
        return payments.markFailed(id, request == null ? null : request.reason());
    }
}
