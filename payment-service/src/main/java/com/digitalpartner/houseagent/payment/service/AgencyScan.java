package com.digitalpartner.houseagent.payment.service;

import com.digitalpartner.houseagent.common.security.AgencyContext;
import com.digitalpartner.houseagent.payment.security.AgencyTenantResolver;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.function.Supplier;

/**
 * Lets the scheduled jobs work across every agency, one agency at a time.
 *
 * <p>Invoicing and arrears are platform-wide sweeps, but the tables they write -
 * {@code invoice}, {@code payment}, {@code payout} - are tenant-filtered. A sweep that
 * did not establish an agency would either write into the no-agency sentinel or read
 * nothing at all, and in neither case would anything fail loudly.
 */
@ApplicationScoped
public class AgencyScan {

    /**
     * Runs work with a CDI request context, activating one if the caller has none.
     *
     * <h2>Why this is needed and {@link AgencyContext} alone is not</h2>
     *
     * Quarkus only asks a {@code TenantResolver} for the tenant when a request context
     * is active; with none it hands Hibernate a null identifier and opening any session
     * fails outright - before {@link AgencyContext} is ever consulted. Setting the
     * agency without a request context therefore achieves nothing.
     *
     * <p>Quarkus's scheduler does this for {@code @Scheduled} methods, so the jobs work
     * on their timers. But these sweeps are also called directly - by tests, and by any
     * future operational endpoint - and a job that only works when the caller happens to
     * have a request context is a trap. Activating one here makes them self-sufficient.
     *
     * <p>A no-op when a context is already active, so nesting is safe.
     */
    public static <T> T inRequestContext(Supplier<T> work) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return work.get();
        }
        requestContext.activate();
        try {
            return work.get();
        } finally {
            requestContext.terminate();
        }
    }

    /**
     * Runs work that touches only untenanted tables.
     *
     * <p>Hibernate needs <em>a</em> tenant identifier to open a session at all, even
     * for entities that carry no discriminator. On a scheduler thread there is no JWT
     * to supply one, so this states the no-agency sentinel explicitly rather than
     * leaving the resolver to fall back to a token that does not exist. The value is
     * irrelevant to the query - it is the act of naming one that matters.
     */
    public static <T> T withoutTenant(Supplier<T> work) {
        return inRequestContext(() ->
                AgencyContext.callWith(AgencyTenantResolver.NO_AGENCY, work));
    }

    /** Every agency that has at least one lease to bill. */
    public List<String> agenciesWithBilling() {
        return withoutTenant(() -> QuarkusTransaction.requiringNew().call(() ->
                Panache.getEntityManager()
                        .createQuery("select distinct b.agencyId from LeaseBilling b", String.class)
                        .getResultList()));
    }
}
