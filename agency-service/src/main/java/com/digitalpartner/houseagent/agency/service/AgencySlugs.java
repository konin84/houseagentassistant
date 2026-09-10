package com.digitalpartner.houseagent.agency.service;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns an agency's name into the id it will be known by.
 *
 * <h2>Why a stranger does not get to choose one</h2>
 *
 * {@code agency_id} is the multi-tenancy discriminator - the value every other service
 * filters its data by. On the platform-admin path a human picks it, because a human is
 * accountable for what they typed. On the open signup path nobody is, and letting an
 * anonymous caller name their own tenancy invites two problems: squatting on the
 * obvious slug for a business that is not theirs, and choosing one that reads like
 * somebody else's in a log line or a support ticket.
 *
 * <p>So the id is derived here from the name they gave, and made unique by this service
 * rather than by whoever asked. It can never be changed afterwards, which is the other
 * reason not to let a signup form decide it.
 */
public final class AgencySlugs {

    /** Comfortably inside the 64-char column, and short enough to read in a log. */
    private static final int MAX_LENGTH = 40;

    /** The pattern the id must match - the same one the platform-admin path validates. */
    private static final int MIN_LENGTH = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The first candidate: the name, lower-cased and stripped to letters and digits.
     *
     * <p>Accents are folded rather than dropped, so "Immobilière Étoile" becomes
     * "immobiliere-etoile" and not "immobili-re-toile". Names outside the Latin script
     * fold to nothing at all, which is why {@link #alternative} exists rather than this
     * method throwing.
     */
    public static String from(String name) {
        String folded = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        String slug = folded.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        // A name that folds away entirely - or to "ab" - still has to produce a usable
        // id. Better a random one than a rejection the person cannot act on, since the
        // id is not something they will ever type.
        if (slug.length() < MIN_LENGTH) {
            return "agency-" + randomSuffix();
        }
        return slug;
    }

    /**
     * The next candidate when one is taken.
     *
     * <p>The first few attempts number the slug, because "moderne-2" is a recognisable
     * second Moderne. After that it gives up on being readable and appends randomness -
     * numbering to the tenth attempt would mean ten round trips to discover that a
     * popular name is popular.
     */
    public static String alternative(String base, int attempt) {
        String trimmed = base.length() > MAX_LENGTH - 8
                ? base.substring(0, MAX_LENGTH - 8).replaceAll("-+$", "")
                : base;
        return attempt <= 3
                ? trimmed + "-" + (attempt + 1)
                : trimmed + "-" + randomSuffix();
    }

    private static String randomSuffix() {
        return Integer.toString(RANDOM.nextInt(0x10000000, 0x7FFFFFFF), 36);
    }

    private AgencySlugs() {
    }
}
