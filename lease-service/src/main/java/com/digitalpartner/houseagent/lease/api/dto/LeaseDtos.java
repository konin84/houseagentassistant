package com.digitalpartner.houseagent.lease.api.dto;

import com.digitalpartner.houseagent.common.events.LeaseEvents.PaymentCadence;
import com.digitalpartner.houseagent.lease.domain.LandlordLeaseView;
import com.digitalpartner.houseagent.lease.domain.Lease;
import com.digitalpartner.houseagent.lease.domain.LeaseStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class LeaseDtos {

    public record SignLeaseRequest(
            @NotNull UUID houseId,
            @NotNull UUID renterId,
            @NotBlank @Size(max = 200) String renterName,
            @Size(max = 40) String renterPhone,
            @NotNull UUID landlordId,
            /** Human-readable house label, so a landlord sees more than a UUID. */
            @Size(max = 200) String houseReference,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal rentAmount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull PaymentCadence cadence,
            @Min(1) @Max(28) int dueDayOfMonth,
            BigDecimal depositAmount,
            @NotNull LocalDate startDate,
            /** Null for an open-ended lease. */
            LocalDate endDate) {
    }

    public record EndLeaseRequest(@Size(max = 200) String reason) {
    }

    /** What agency staff see. Includes both parties and the full terms. */
    public record LeaseResponse(
            UUID id,
            UUID houseId,
            String houseReference,
            UUID renterId,
            String renterName,
            String renterPhone,
            UUID landlordId,
            BigDecimal rentAmount,
            String currency,
            PaymentCadence cadence,
            int dueDayOfMonth,
            BigDecimal depositAmount,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate firstDueDate,
            LeaseStatus status,
            String endReason) {

        public static LeaseResponse from(Lease lease) {
            return new LeaseResponse(
                    lease.id,
                    lease.houseId,
                    lease.houseReference,
                    lease.renterId,
                    lease.renterName,
                    lease.renterPhone,
                    lease.landlordId,
                    lease.modality.rentAmount,
                    lease.modality.currency,
                    lease.modality.cadence,
                    lease.modality.dueDayOfMonth,
                    lease.modality.depositAmount,
                    lease.startDate,
                    lease.endDate,
                    lease.modality.firstDueDate(lease.startDate),
                    lease.status,
                    lease.endReason);
        }
    }

    /**
     * What a landlord sees: who is in their house and on what terms.
     *
     * <p>The deposit is omitted - it is held by the agency, not the landlord, and
     * showing it invites disputes the platform has no role in. The agency is shown so
     * the landlord knows who to call.
     */
    public record LandlordLeaseResponse(
            UUID leaseId,
            UUID houseId,
            String houseReference,
            String managedByAgency,
            UUID renterId,
            String renterName,
            String renterPhone,
            BigDecimal rentAmount,
            String currency,
            PaymentCadence cadence,
            int dueDayOfMonth,
            LocalDate startDate,
            LocalDate endDate,
            LeaseStatus status) {

        public static LandlordLeaseResponse from(LandlordLeaseView view) {
            return new LandlordLeaseResponse(
                    view.leaseId,
                    view.houseId,
                    view.houseReference,
                    view.agencyId,
                    view.renterId,
                    view.renterName,
                    view.renterPhone,
                    view.rentAmount,
                    view.currency,
                    view.cadence,
                    view.dueDayOfMonth,
                    view.startDate,
                    view.endDate,
                    view.status);
        }
    }

    private LeaseDtos() {
    }
}
