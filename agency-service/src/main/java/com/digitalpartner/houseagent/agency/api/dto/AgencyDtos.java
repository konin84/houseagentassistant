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

    /**
     * An agency signing itself up, sent by somebody with no account and no token.
     *
     * <p>Read this record for what is missing as much as what is here. There is no
     * {@code agencyId} - the id is derived from the name, because it is the tenancy
     * discriminator and not a stranger's to choose. There is no {@code plan} - every
     * signup lands on the free one. There is no {@code role} - it is always
     * {@code AGENCY_ADMIN}. Each of those would be a field an attacker fills in.
     *
     * @param password the person's own, not one this service generates. A temporary
     *                 password would have to be returned over an unauthenticated
     *                 response and then immediately changed, which is two secrets in
     *                 flight where one will do.
     */
    public record SignupRequest(
            @NotBlank @Size(min = 2, max = 200) String agencyName,
            @Size(max = 120) String city,
            @Pattern(regexp = "[A-Z]{2}", message = "must be a 2-letter ISO country code")
            String countryCode,
            @Size(max = 40) String contactPhone,
            @NotBlank @Email @Size(max = 320) String adminEmail,
            @Size(max = 200) String firstName,
            @Size(max = 200) String lastName,
            /**
             * The administrator's own number, which they can then sign in with.
             * Distinct from contactPhone above, which is the agency's - they are often
             * the same number and are not the same thing.
             */
            @Size(max = 40) String adminPhone,
            /**
             * Eight is what the development realm's seeded accounts use, so the API and
             * the realm agree. A deployment should raise both together - the realm's
             * {@code passwordPolicy} is the one that cannot be bypassed, since it also
             * governs every later password change.
             */
            @NotBlank @Size(min = 8, max = 128) String password) {
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
            @Size(max = 200) String lastName,
            /**
             * Optional, and the reason this record changed.
             *
             * <p>Given one, the person can sign in with it instead of their email. A
             * landlord who deals in houses and cash knows their number by heart and may
             * check their email monthly; making them use the address is how you lose
             * them at the login screen.
             *
             * <p>Any format a person would write: {@code 07 00 00 00 00},
             * {@code +225 07-00-00-00-00}, {@code 00225 0700000000}. It is reduced to
             * one form on the way in, because the number is a login identifier and two
             * spellings of it would mean an account somebody cannot sign in to.
             */
            @Size(max = 40) String phone) {
    }

    public record OnboardPartyRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 200) String firstName,
            @Size(max = 200) String lastName,
            /**
             * Optional, and the reason this record changed.
             *
             * <p>Given one, the person can sign in with it instead of their email. A
             * landlord who deals in houses and cash knows their number by heart and may
             * check their email monthly; making them use the address is how you lose
             * them at the login screen.
             *
             * <p>Any format a person would write: {@code 07 00 00 00 00},
             * {@code +225 07-00-00-00-00}, {@code 00225 0700000000}. It is reduced to
             * one form on the way in, because the number is a login identifier and two
             * spellings of it would mean an account somebody cannot sign in to.
             */
            @Size(max = 40) String phone) {
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

    /**
     * The result of a signup: an agency, and the person who may act for it.
     *
     * <p>No credential comes back. The caller chose their own password and already has
     * it, so there is nothing here worth intercepting - which is the point of having
     * them choose it rather than being handed one.
     *
     * @param nextStep what to actually do now, because the reply to a signup is the one
     *                 place a client has no earlier response to infer it from
     */
    public record SignupResponse(
            AgencyResponse agency,
            UserResponse administrator,
            String nextStep) {

        public static SignupResponse from(Agency agency, PlatformUser administrator) {
            return new SignupResponse(
                    AgencyResponse.from(agency),
                    UserResponse.from(administrator),
                    "Check your email and click the link to confirm the address, then "
                            + "sign in with the password you just chose. Signing in "
                            + "before that answers 'Account is not fully set up'. Your "
                            + "agency is on the " + agency.plan + " plan, which allows "
                            + agency.plan.maxHouses() + " houses. Contact the platform "
                            + "to move to a larger one.");
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
            /**
             * Normalised, or null if they gave none. Show this back rather than what was
             * typed - it is what they will have to enter to sign in.
             */
            String phone,
            boolean enabled) {

        public static UserResponse from(PlatformUser user) {
            return new UserResponse(
                    user.userId(), user.email(), user.firstName(), user.lastName(),
                    user.roles(), user.agencyId(), user.partyId(), user.phone(),
                    user.enabled());
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
