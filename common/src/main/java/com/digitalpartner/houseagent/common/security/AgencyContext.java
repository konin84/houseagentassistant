package com.digitalpartner.houseagent.common.security;

import java.util.function.Supplier;

/**
 * Explicitly establishes the agency for work that has no HTTP request behind it.
 *
 * <h2>Why this exists</h2>
 *
 * The tenant is normally read from the caller's JWT. Kafka consumers and scheduled
 * jobs have no JWT and no request context, so a resolver that only knows how to read
 * a token would fall back to a default for them.
 *
 * <p>That fallback is the single most common way multi-tenant systems leak data: a
 * consumer processing agency B's event writes it into agency A's data, or into
 * whichever tenant the default happens to name, and nothing fails loudly. Every such
 * entry point must therefore state the agency it is acting for, and this class is how.
 *
 * <p>The value is thread-bound and always cleared in a finally block, because
 * consumer threads are pooled and reused - a leaked value would silently apply to the
 * next, unrelated message.
 */
public final class AgencyContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** Runs {@code action} with the given agency established as the current tenant. */
    public static void runWith(String agencyId, Runnable action) {
        String previous = CURRENT.get();
        CURRENT.set(agencyId);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    /** As {@link #runWith}, for work that produces a value. */
    public static <T> T callWith(String agencyId, Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(agencyId);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** The explicitly established agency, or null when there is none. */
    public static String current() {
        return CURRENT.get();
    }

    private static void restore(String previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private AgencyContext() {
    }
}
