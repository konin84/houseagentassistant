package com.digitalpartner.houseagent.agency;

import com.digitalpartner.houseagent.agency.identity.InvalidPhoneNumberException;
import com.digitalpartner.houseagent.agency.identity.PhoneNumbers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The number is a login identifier, so this is really a test about identity.
 *
 * <p>Every case below is the same question asked differently: does the way a person
 * happened to write their number decide whether they can get in? If two spellings of one
 * number produce two strings, the same human can be registered twice and somebody who
 * signed up in one format cannot sign in using the other - and that second failure
 * reaches them as "wrong password", which they cannot act on.
 */
class PhoneNumbersTest {

    private static final String CI = "225";

    @Test
    void everyWayAPersonWritesTheirNumberIsTheSameNumber() {
        String expected = "+2250700000000";

        // Nobody writes a phone number the same way twice, least of all on a form.
        assertEquals(expected, PhoneNumbers.normalize("0700000000", CI));
        assertEquals(expected, PhoneNumbers.normalize("07 00 00 00 00", CI));
        assertEquals(expected, PhoneNumbers.normalize("07-00-00-00-00", CI));
        assertEquals(expected, PhoneNumbers.normalize("07.00.00.00.00", CI));
        assertEquals(expected, PhoneNumbers.normalize("+225 07 00 00 00 00", CI));
        assertEquals(expected, PhoneNumbers.normalize("+225-0700000000", CI));
        assertEquals(expected, PhoneNumbers.normalize("00225 07 00 00 00 00", CI));
        assertEquals(expected, PhoneNumbers.normalize("(+225) 07 00 00 00 00", CI));
        assertEquals(expected, PhoneNumbers.normalize("  +2250700000000  ", CI));
    }

    @Test
    void normalisingTwiceChangesNothing() {
        // It has to be safe to run over a stored value, because otherwise a second pass
        // somewhere - a migration, a re-save - would quietly rewrite somebody's login.
        String once = PhoneNumbers.normalize("07 00 00 00 00", CI);
        assertEquals(once, PhoneNumbers.normalize(once, CI));
    }

    @Test
    void aNumberFromAnotherCountryKeepsItsOwnCode() {
        // The default applies only when the person did not say. Stamping +225 on a
        // number that already carries +33 would hand them somebody else's identifier.
        assertEquals("+33612345678", PhoneNumbers.normalize("+33 6 12 34 56 78", CI));
        assertEquals("+22177123456", PhoneNumbers.normalize("00221 77 123 456", CI));
    }

    @Test
    void noNumberIsNotAnError() {
        // The field is optional. Somebody who gives no number simply signs in with their
        // email, which is how every account on this platform worked until now.
        assertNull(PhoneNumbers.normalize(null, CI));
        assertNull(PhoneNumbers.normalize("", CI));
        assertNull(PhoneNumbers.normalize("   ", CI));
    }

    @Test
    void somethingThatIsNotANumberIsRefusedRatherThanStored() {
        // Storing these would create an account whose owner can never sign in with the
        // thing they were told to sign in with.
        assertThrows(InvalidPhoneNumberException.class,
                () -> PhoneNumbers.normalize("0700", CI));           // too short
        assertThrows(InvalidPhoneNumberException.class,
                () -> PhoneNumbers.normalize("07000000000000000000", CI));  // too long
        assertThrows(InvalidPhoneNumberException.class,
                () -> PhoneNumbers.normalize("not a phone", CI));    // no digits at all
    }

    @Test
    void theKnownLimitIsATrunkPrefix() {
        // Documented rather than fixed. A French number in local form keeps its leading
        // zero, because dropping a trunk code correctly needs a table of every country's
        // rules - which is what libphonenumber is for, and what this is not.
        //
        // This test exists so the limitation is discovered here rather than by a
        // customer in Paris, and so it fails loudly the day somebody implements it.
        assertEquals("+2250612345678", PhoneNumbers.normalize("06 12 34 56 78", CI),
                "a local number is assumed to belong to the default country");
    }
}
