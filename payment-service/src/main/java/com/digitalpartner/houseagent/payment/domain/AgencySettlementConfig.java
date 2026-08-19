package com.digitalpartner.houseagent.payment.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * One agency's answer to "does the platform hold the money, and what does it keep?".
 *
 * <p>No {@code @TenantId} here, deliberately. The invoice and arrears jobs run on a
 * scheduler with no JWT and have to read every agency's policy; a filtered entity
 * would make them silently see nothing. Reads from the API are narrowed by an explicit
 * agency predicate in {@code SettlementPolicy} instead, which is the same trade this
 * codebase makes for {@code outbox_event}.
 */
@Entity
@Table(name = "agency_settlement_config")
public class AgencySettlementConfig extends PanacheEntityBase {

    /** The agency this policy belongs to. */
    @Id
    @Column(name = "agency_id", nullable = false, updatable = false, length = 64)
    public String agencyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    public SettlementMode mode = SettlementMode.DEFAULT;

    /**
     * Commission in basis points: 750 is 7.5%.
     *
     * <p>An integer rather than a decimal percentage, because the rate is the one
     * number here that gets multiplied by money. Storing 7.1% as a double and
     * multiplying is how a payout ends up a centime short of what the contract says.
     */
    @Column(name = "commission_bps", nullable = false)
    public int commissionBps;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * The agency's cut of a settled payment, rounded to the currency's minor unit.
     *
     * <p>Rounds <em>down</em>, so any indivisible remainder goes to the landlord. The
     * rounding has to favour someone and favouring the party whose money it is avoids
     * an agency quietly collecting a fraction more than its stated rate.
     */
    public BigDecimal commissionOn(BigDecimal amount) {
        if (!mode.platformHoldsTheMoney() || commissionBps == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        return amount.multiply(BigDecimal.valueOf(commissionBps))
                .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.DOWN);
    }

    public static AgencySettlementConfig defaultsFor(String agencyId) {
        AgencySettlementConfig config = new AgencySettlementConfig();
        config.agencyId = agencyId;
        config.mode = SettlementMode.DEFAULT;
        config.commissionBps = 0;
        return config;
    }
}
