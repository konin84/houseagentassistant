package com.digitalpartner.houseagent.payment.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.api.dto.PageResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.InvoiceResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.PayoutResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.SettlementConfigRequest;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.SettlementConfigResponse;
import com.digitalpartner.houseagent.payment.security.CallerContext;
import com.digitalpartner.houseagent.payment.service.AgencyBilling;
import com.digitalpartner.houseagent.payment.service.SettlementPolicy;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Invoices, payouts and settlement policy for agency staff.
 *
 * <p>No endpoint takes an {@code agencyId}. The agency comes from the token and
 * Hibernate's tenant filter applies it, so there is nothing for a caller to tamper
 * with.
 */
@Path("/api/agency")
@Tag(name = "Agency billing", description = "Invoices, payouts and settlement policy")
@RolesAllowed({Roles.AGENT, Roles.AGENCY_ADMIN})
@Produces(MediaType.APPLICATION_JSON)
public class AgencyBillingResource {

    @Inject
    AgencyBilling billing;

    @Inject
    SettlementPolicy policy;

    @Inject
    CallerContext caller;

    // ---------------------------------------------------------------- invoices

    @GET
    @Path("/invoices")
    public PageResponse<InvoiceResponse> invoices(
            @QueryParam("unpaidOnly") @DefaultValue("false") boolean unpaidOnly,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        caller.requireAgencyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), AgencyBilling.MAX_PAGE_SIZE);

        List<InvoiceResponse> items = billing.invoices(unpaidOnly, safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, billing.countInvoices(unpaidOnly));
    }

    /** The chase list: everything past its due date and not yet paid. */
    @GET
    @Path("/invoices/overdue")
    public PageResponse<InvoiceResponse> overdue(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        caller.requireAgencyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), AgencyBilling.MAX_PAGE_SIZE);

        List<InvoiceResponse> items = billing.overdueInvoices(safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, items.size());
    }

    @GET
    @Path("/invoices/{id}")
    public InvoiceResponse invoice(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return billing.invoice(id);
    }

    // ----------------------------------------------------------------- payouts

    @GET
    @Path("/payouts")
    public PageResponse<PayoutResponse> payouts(
            @QueryParam("pendingOnly") @DefaultValue("true") boolean pendingOnly,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        caller.requireAgencyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), AgencyBilling.MAX_PAGE_SIZE);

        List<PayoutResponse> items = billing.payouts(pendingOnly, safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, billing.countPayouts(pendingOnly));
    }

    /** Records that the landlord's share has been remitted. */
    @POST
    @Path("/payouts/{id}/settlement")
    public PayoutResponse settlePayout(@PathParam("id") UUID id) {
        caller.requireAgencyId();
        return billing.markPayoutSettled(id);
    }

    // ------------------------------------------------------- settlement policy

    @GET
    @Path("/settlement-config")
    public SettlementConfigResponse settlementConfig() {
        return SettlementConfigResponse.from(policy.forAgency(caller.requireAgencyId()));
    }

    /**
     * Sets whether the platform collects rent, and what the agency keeps.
     *
     * <p>{@code AGENCY_ADMIN} only. This is a commercial term, not day-to-day letting
     * work, and an agent who could raise the commission could quietly change what every
     * landlord on the books is paid.
     */
    @PUT
    @Path("/settlement-config")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.AGENCY_ADMIN)
    public SettlementConfigResponse configureSettlement(@Valid SettlementConfigRequest request) {
        return SettlementConfigResponse.from(
                policy.configure(caller.requireAgencyId(), request.mode(), request.commissionBps()));
    }
}
