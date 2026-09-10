package com.digitalpartner.houseagent.agency.identity;

/**
 * Puts a phone number into the one form this platform stores it in.
 *
 * <h2>Why this matters more than it looks</h2>
 *
 * The number is a <em>login identifier</em>, not a piece of contact data. If
 * {@code 07 00 00 00 00} and {@code +225 07 00 00 00 00} do not reduce to the same
 * string, then two things both go wrong: the same person can be registered twice, and
 * somebody who signs up in one format cannot sign in using the other. Neither failure
 * announces itself - the second looks to the person like a wrong password.
 *
 * <p>So every number is reduced here, once, on the way in. Nothing else in the codebase
 * should be comparing phone numbers by string.
 *
 * <h2>What it does, and what it does not</h2>
 *
 * Punctuation goes, {@code 00} becomes {@code +}, and a number with no country code gets
 * the configured default. That covers Côte d'Ivoire, where numbers are ten digits and
 * the international form is the country code followed by all ten of them.
 *
 * <p>It is <b>not</b> a general phone number library. Countries with a trunk prefix -
 * France, where {@code 06 12 …} becomes {@code +33 6 12 …} and the leading zero is
 * dropped - would come out wrong if entered in local form, because stripping a trunk
 * code correctly needs a table of every country's rules. Callers outside the default
 * country should enter the full {@code +} form, which is passed through untouched.
 *
 * <p>Google's libphonenumber does all of this properly. It is worth adopting the day
 * this platform has agencies in a country with a trunk prefix; it is a large dependency
 * to carry before then.
 */
public final class PhoneNumbers {

    /** Long enough to be a real number anywhere, short enough to reject typing errors. */
    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;   // E.164's own limit

    /**
     * Reduces a number to {@code +} followed by digits, or returns null for blank input.
     *
     * @param defaultCallingCode digits only, no {@code +} - applied when the number
     *                           carries no country code of its own
     * @throws InvalidPhoneNumberException if what is left is not a plausible number
     */
    public static String normalize(String raw, String defaultCallingCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // Drop anything before the first + or digit, so a number written "(+225) 07 …"
        // is still recognised as carrying its own country code. Missing that leads
        // somewhere worse than a rejection: the default code gets applied on top of the
        // one already there, and the person ends up with a number nobody can dial.
        String trimmed = raw.trim().replaceFirst("^[^+0-9]+", "");
        boolean international = trimmed.startsWith("+") || trimmed.startsWith("00");

        // Everything that is not a digit goes: spaces, dots, hyphens, brackets. People
        // write their own number a dozen ways and every one of them is the same number.
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (trimmed.startsWith("00")) {
            digits = digits.substring(2);
        }

        if (digits.isEmpty()) {
            throw new InvalidPhoneNumberException(raw);
        }

        String full = international ? digits : defaultCallingCode + digits;

        if (full.length() < MIN_DIGITS || full.length() > MAX_DIGITS) {
            throw new InvalidPhoneNumberException(raw);
        }
        return "+" + full;
    }

    private PhoneNumbers() {
    }
}
