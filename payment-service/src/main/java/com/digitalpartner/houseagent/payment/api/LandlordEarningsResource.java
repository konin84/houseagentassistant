package com.digitalpartner.houseagent.payment.api;

import com.digitalpartner.houseagent.common.security.Roles;
import com.digitalpartner.houseagent.payment.api.dto.PageResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.BalanceResponse;
import com.digitalpartner.houseagent.payment.api.dto.PaymentDtos.LandlordEarningResponse;
import com.digitalpartner.houseagent.payment.security.CallerContext;
import com.digitalpartner.houseagent.payment.service.LandlordEarnings;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * What a landlord has been paid, across every agency that manages a house for them.
 *
 * <p>The email a landlord receives when rent settles tells them it happened; this is
 * where they check the arithmetic. As everywhere on this platform, the identity comes
 * from the token and never from a parameter.
 */
@Path("/api/landlord/earnings")
@Tag(name = "Landlord earnings", description = "Rent received, net of commission")
@RolesAllowed(Roles.LANDLORD)
@Produces(MediaType.APPLICATION_JSON)
public class LandlordEarningsResource {

    @Inject
    LandlordEarnings earnings;

    @Inject
    CallerContext caller;

    @GET
    public PageResponse<LandlordEarningResponse> myEarnings(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        UUID landlordId = caller.requirePartyId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), LandlordEarnings.MAX_PAGE_SIZE);

        List<LandlordEarningResponse> items = earnings.earningsOf(landlordId, safePage, safeSize);
        return PageResponse.of(items, safePage, safeSize, earnings.countEarningsOf(landlordId));
    }

    /** Total received to date, net of whatever commission applied at the time. */
    @GET
    @Path("/total")
    public BalanceResponse myTotal() {
        UUID landlordId = caller.requirePartyId();
        List<LandlordEarningResponse> first = earnings.earningsOf(landlordId, 0, 1);
        return new BalanceResponse(
                earnings.totalNetReceived(landlordId),
                first.isEmpty() ? null : first.getFirst().currency());
    }
}
