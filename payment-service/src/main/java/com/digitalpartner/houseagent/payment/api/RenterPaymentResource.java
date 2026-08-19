package com.digitalpartner.houseagent.payment.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.api.dto.PageResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.BalanceResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.InitiatePaymentRequest;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.PaymentResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.RenterInvoiceResponse;
import com.digitalpartner.houseagent.payment.security.CallerContext;
import com.digitalpartner.houseagent.payment.service.PaymentService;
import com.digitalpartner.houseagent.payment.service.RenterLedger;
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
 * A renter's own rent: what they owe, and paying it.
 *
 * <p>Note the absence of a {@code renterId} parameter on every method. The identity
 * comes from the {@code party_id} claim. Accepting it from the path would turn this
 * resource into a way for anyone to read - and pay from - anyone else's account, and
 * because renters are not agency-scoped there is no tenant filter underneath to catch
 * the mistake.
 */
@Path("/api/renter")
@Tag(name = "Renter payments", description = "A renter's own rent")
@RolesAllowed(Roles.RENTER)
@Produces(MediaType.APPLICATION_JSON)
public class RenterPaymentResource {

    @Inject
    RenterLedger ledger;

    @Inject
    PaymentService payments;

    @Inject
    CallerContext caller;

    /**
     * @param unpaidOnly when true, only what is still owed; when false, the full
     *                   history including invoices already paid
     */
    @GET
    @Path("/invoices")
    public PageResponse<RenterInvoiceResponse> myInvoices(
            @QueryParam("unpaidOnly") @DefaultValue("true") boolean unpaidOnly,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        UUID renterId = caller.requirePartyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), RenterLedger.MAX_PAGE_SIZE);

        List<RenterInvoiceResponse> items =
                ledger.invoicesOf(renterId, unpaidOnly, safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize,
                ledger.countInvoicesOf(renterId, unpaidOnly));
    }

    @GET
    @Path("/invoices/{invoiceId}")
    public RenterInvoiceResponse myInvoice(@PathParam("invoiceId") UUID invoiceId) {
        return ledger.invoiceOf(caller.requirePartyId(), invoiceId);
    }

    /** One number: everything this renter currently owes. */
    @GET
    @Path("/balance")
    public BalanceResponse myBalance() {
        UUID renterId = caller.requirePartyId();
        var outstanding = ledger.totalOutstanding(renterId);
        var first = ledger.invoicesOf(renterId, true, 0, 1);
        // Currency comes from the invoices themselves rather than being assumed: an
        // agency may operate across borders.
        return new BalanceResponse(outstanding, first.isEmpty() ? null : first.getFirst().currency());
    }

    /**
     * Starts paying one of the caller's own invoices.
     *
     * <p>Lands {@code PENDING}: initiating a mobile money push is not the same as the
     * money arriving. The agency or the provider's callback confirms it separately, and
     * only that marks the invoice paid.
     */
    @POST
    @Path("/invoices/{invoiceId}/payments")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pay(@PathParam("invoiceId") UUID invoiceId,
                        @Valid InitiatePaymentRequest request) {

        PaymentResponse initiated =
                payments.initiateAsRenter(invoiceId, caller.requirePartyId(), request);
        return Response.created(URI.create("/api/renter/payments/" + initiated.id()))
                .entity(initiated)
                .build();
    }
}
