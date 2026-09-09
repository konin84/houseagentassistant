package com.digitalpartner.houseagent.agency.api.dto;

import com.digitalpartner.houseagent.agency.domain.Agency;
import com.digitalpartner.houseagent.agency.domain.AgencyStatus;
import com.digitalpartner.houseagent.agency.domain.SubscriptionPlan;
import com.digitalpartner.houseagent.agency.identity.PlatformUser;
import com.digitalpartner.houseagent.agency.service.StaffDirectory.Provisioned;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public final class AgencyDtos {

    // ---------------------------------------------------------------- requests

    public record RegisterAgencyRequest(
            /** Lower-case slug. Becomes the agency_id claim and can never be changed. */
            @NotBlank @Size(min = 3, max = 64)
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$",
                     message = "must be lower-case letters, digits and hyphens")
            String agencyId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 120) String city,
            @Pattern(regexp = "[A-Z]{2}", message = "must be a 2-letter ISO country code")
            String countryCode,
            @Email @Size(max = 320) String contactEmail,
            @Size(max = 40) String contactPhone) {
    }

    /**
     * Platform-admin only. Without payment behind it, an agency able to set its own
     * plan could award itself the unlimited tier.
     */
    public record ChangePlanRequest(@NotNull SubscriptionPlan plan) {
    }

    public record UpdateAgencyRequest(
            @Size(max = 200) String name,
            @Size(max = 120) String city,
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Email @Size(max = 320) String contactEmail,
            @Size(max = 40) String contactPhone) {
    }

    /**
     * Note what is absent: any mention of an agency.
     *
     * <p>It comes from the caller's token. A field here would be the one thing that
     * lets an agency admin place staff inside a competitor.
     */
    public record CreateStaffRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 200) String firstName,
            @Size(max = 200) String lastName) {
    }

    public record OnboardPartyRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 200) String firstName,
            @Size(max = 200) String lastName) {
    }

    // --------------------------------------------------------------- responses

    /**
     * @param maxHouses the ceiling this plan allows, or null for unlimited. Derived
     *                  from the plan rather than stored, so raising a tier is a one-line
     *                  change instead of an UPDATE across every customer.
     */
    public record AgencyResponse(
            String agencyId,
            String name,
            String city,
            String countryCode,
            String contactEmail,
            String contactPhone,
            AgencyStatus status,
            SubscriptionPlan plan,
            Integer maxHouses,
            Instant planChangedAt,
            Instant createdAt) {

        public static AgencyResponse from(Agency agency) {
            return new AgencyResponse(
                    agency.agencyId, agency.name, agency.city, agency.countryCode,
                    agency.contactEmail, agency.contactPhone, agency.status,
                    agency.plan, agency.plan.maxHouses(), agency.planChangedAt,
                    agency.createdAt);
        }
    }

    public record UserResponse(
            String userId,
            String email,
            String firstName,
            String lastName,
            Set<String> roles,
            String agencyId,
            /** What to use as landlordId on a house, or renterId on a lease. */
            String partyId,
            boolean enabled) {

        public static UserResponse from(PlatformUser user) {
            return new UserResponse(
                    user.userId(), user.email(), user.firstName(), user.lastName(),
                    user.roles(), user.agencyId(), user.partyId(), user.enabled());
        }
    }

    /**
     * A newly provisioned or newly linked person.
     *
     * @param linked            true when this account already existed and was attached
     *                          rather than created - the normal case for a landlord who
     *                          already works with another agency
     * @param temporaryPassword shown once and never retrievable again. Null when
     *                          {@code linked}, because the person already has a
     *                          password and handing an agency a fresh one for somebody
     *                          else's account would be a takeover, not an introduction.
     */
    public record ProvisionedResponse(
            UserResponse user,
            boolean linked,
            String temporaryPassword,
            String note) {

        public static ProvisionedResponse from(Provisioned provisioned) {
            String note = provisioned.linked()
                    ? "This person already had an account; it has been linked rather "
                      + "than duplicated, so their existing password still applies."
                    : "Pass the temporary password on. It must be changed at first "
                      + "login and is not shown again.";
            return new ProvisionedResponse(
                    UserResponse.from(provisioned.user()),
                    provisioned.linked(),
                    provisioned.temporaryPassword(),
                    note);
        }
    }

    private AgencyDtos() {
    }
}
