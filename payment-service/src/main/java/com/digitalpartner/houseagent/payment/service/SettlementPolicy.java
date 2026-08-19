package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.payment.domain.AgencySettlementConfig;
import com.digitalpartner.houseagent.payment.domain.SettlementMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;

/**
 * The one place that answers "does this agency hold the money, and what does it keep?".
 *
 * <h2>Why this is a small class of its own</h2>
 *
 * Whether the platform collects rent changes what happens on every settlement: a
 * payout row is written or it is not, a commission is deducted or it is not, and a
 * landlord is told a net figure or a gross one. Scattering that condition across the
 * settlement path would mean a future change to one branch silently disagreeing with
 * another - an agency taking commission but owing the landlord the full amount, or the
 * reverse.
 *
 * <p>So the branch is read once, here, and everything downstream consumes the
 * resulting {@link AgencySettlementConfig} rather than re-deciding.
 *
 * <p>{@code agency_settlement_config} carries no {@code @TenantId}, because the
 * scheduled jobs must read every agency's policy. The {@code agencyId} argument below
 * is therefore the access control on the API path, and it always comes from the
 * validated token.
 */
@ApplicationScoped
public class SettlementPolicy {

    /**
     * The agency's policy, or safe defaults if it has never set one.
     *
     * <p>Never returns null and never creates a row as a side effect of a read. An
     * agency that has not configured anything behaves as {@link SettlementMode#DEFAULT}
     * - the platform holds no money - which is the direction it is safe to be wrong in.
     */
    @Transactional
    public AgencySettlementConfig forAgency(String agencyId) {
        AgencySettlementConfig config = AgencySettlementConfig.findById(agencyId);
        return config != null ? config : AgencySettlementConfig.defaultsFor(agencyId);
    }

    /**
     * Sets an agency's policy.
     *
     * @throws IllegalSettlementConfigException when a commission is set on an agency
     *                                          that does not collect - the platform
     *                                          cannot take a cut of money it never
     *                                          touches, and silently zeroing it would
     *                                          hide a misconfiguration
     */
    @Transactional
    public AgencySettlementConfig configure(String agencyId, SettlementMode mode, int commissionBps) {
        if (commissionBps < 0 || commissionBps > 10_000) {
            throw new IllegalSettlementConfigException(
                    "Commission must be between 0 and 10000 basis points, got " + commissionBps);
        }
        if (!mode.platformHoldsTheMoney() && commissionBps != 0) {
            throw new IllegalSettlementConfigException(
                    "Commission cannot be charged under " + mode
                            + ", because the platform never holds the rent");
        }

        AgencySettlementConfig config = AgencySettlementConfig.findById(agencyId);
        if (config == null) {
            config = AgencySettlementConfig.defaultsFor(agencyId);
            config.persist();
        }
        config.mode = mode;
        config.commissionBps = commissionBps;
        config.updatedAt = Instant.now();
        return config;
    }
}
