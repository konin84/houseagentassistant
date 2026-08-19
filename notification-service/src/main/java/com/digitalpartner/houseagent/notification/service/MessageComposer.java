package com.digitalpartner.houseagent.notification.service;

import com.digitalpartner.houseagent.notification.domain.Contact;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.RoundingMode;

/**
 * Writes the actual words a landlord or renter receives.
 *
 * <p>French first, because that is the language of this market; English is there for
 * anyone whose contact says so. A rent notice in the wrong language gets ignored rather
 * than translated, which makes {@link Contact#locale} a functional field and not a
 * decorative one.
 *
 * <p>Composed in Java rather than through a template engine on purpose, for now. There
 * are two messages, they are asserted on directly in the tests, and a template file
 * adds a rendering step between the event and the words without changing them. When
 * there are ten of these, and marketing wants to edit them, Qute is the answer.
 */
@ApplicationScoped
public class MessageComposer {

    /** A composed message, ready to be logged and sent. */
    public record Message(String subject, String body) {
    }

    public Message compose(String eventType, JsonNode payload, Contact recipient, Audience audience) {
        boolean french = isFrench(recipient);
        return switch (eventType) {
            case "PaymentSettled" -> french
                    ? paymentSettledFr(payload, recipient)
                    : paymentSettledEn(payload, recipient);
            case "RentOverdue" -> french
                    ? rentOverdueFr(payload, recipient, audience)
                    : rentOverdueEn(payload, recipient, audience);
            default -> throw new IllegalArgumentException("No template for " + eventType);
        };
    }

    /** Who the message is addressed to, which changes what it can reasonably say. */
    public enum Audience {
        LANDLORD,
        RENTER
    }

    // ------------------------------------------------------------ PaymentSettled

    private Message paymentSettledFr(JsonNode p, Contact to) {
        String house = houseOf(p);
        String money = money(p, "netAmount");

        String subject = "Loyer reçu - " + house;
        String body = """
                Bonjour %s,

                Le loyer de %s a été réglé.

                Bien           : %s
                Locataire      : %s
                Période        : du %s au %s
                Montant reçu   : %s
                Commission     : %s
                Net pour vous  : %s

                Réglé le %s.

                Vous pouvez consulter le détail de vos encaissements sur votre espace
                propriétaire.
                """.formatted(
                greeting(to),
                text(p, "renterName", "votre locataire"),
                house,
                text(p, "renterName", "-"),
                text(p, "periodStart", "-"),
                text(p, "periodEnd", "-"),
                money(p, "amount"),
                money(p, "commissionAmount"),
                money,
                text(p, "settledAt", "-"));

        return new Message(subject, body);
    }

    private Message paymentSettledEn(JsonNode p, Contact to) {
        String house = houseOf(p);

        String subject = "Rent received - " + house;
        String body = """
                Hello %s,

                Rent from %s has been paid.

                Property     : %s
                Renter       : %s
                Period       : %s to %s
                Amount paid  : %s
                Commission   : %s
                Net to you   : %s

                Settled on %s.

                The full breakdown is available in your landlord portal.
                """.formatted(
                greeting(to),
                text(p, "renterName", "your renter"),
                house,
                text(p, "renterName", "-"),
                text(p, "periodStart", "-"),
                text(p, "periodEnd", "-"),
                money(p, "amount"),
                money(p, "commissionAmount"),
                money(p, "netAmount"),
                text(p, "settledAt", "-"));

        return new Message(subject, body);
    }

    // --------------------------------------------------------------- RentOverdue

    private Message rentOverdueFr(JsonNode p, Contact to, Audience audience) {
        String house = houseOf(p);
        String days = text(p, "daysOverdue", "?");

        if (audience == Audience.RENTER) {
            return new Message(
                    "Loyer en retard - " + house,
                    """
                    Bonjour %s,

                    Le loyer du bien %s est en retard de %s jour(s).

                    Échéance      : %s
                    Montant dû    : %s

                    Merci de régulariser dès que possible, ou de contacter votre agence
                    si le règlement a déjà été effectué.
                    """.formatted(greeting(to), house, days,
                            text(p, "dueDate", "-"), money(p, "amountDue")));
        }

        return new Message(
                "Loyer impayé - " + house,
                """
                Bonjour %s,

                Le loyer de votre bien %s n'a pas encore été réglé.

                Locataire     : %s
                Échéance      : %s
                Montant dû    : %s
                Retard        : %s jour(s)

                Votre agence a été informée et se charge du recouvrement.
                """.formatted(greeting(to), house, text(p, "renterName", "-"),
                        text(p, "dueDate", "-"), money(p, "amountDue"), days));
    }

    private Message rentOverdueEn(JsonNode p, Contact to, Audience audience) {
        String house = houseOf(p);
        String days = text(p, "daysOverdue", "?");

        if (audience == Audience.RENTER) {
            return new Message(
                    "Rent overdue - " + house,
                    """
                    Hello %s,

                    Rent for %s is %s day(s) overdue.

                    Due date   : %s
                    Amount due : %s

                    Please settle it as soon as you can, or contact your agency if you
                    have already paid.
                    """.formatted(greeting(to), house, days,
                            text(p, "dueDate", "-"), money(p, "amountDue")));
        }

        return new Message(
                "Rent unpaid - " + house,
                """
                Hello %s,

                Rent for your property %s has not yet been paid.

                Renter     : %s
                Due date   : %s
                Amount due : %s
                Overdue by : %s day(s)

                Your agency has been notified and is following it up.
                """.formatted(greeting(to), house, text(p, "renterName", "-"),
                        text(p, "dueDate", "-"), money(p, "amountDue"), days));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isFrench(Contact contact) {
        // Default to French: the market is francophone, and an unset locale should not
        // silently mean "write to this person in a language they may not read".
        return contact.locale == null || contact.locale.toLowerCase().startsWith("fr");
    }

    private static String greeting(Contact contact) {
        return contact.displayName == null || contact.displayName.isBlank()
                ? "" : contact.displayName;
    }

    private static String houseOf(JsonNode payload) {
        // Falls back to the lease id rather than printing "null" at someone. A landlord
        // with one property still knows which it is; one with twelve at least has
        // something to quote to their agency.
        String reference = text(payload, "houseReference", null);
        return reference != null ? reference : "bien " + text(payload, "leaseId", "?");
    }

    /**
     * Formats an amount for a human to read.
     *
     * <p>Goes through {@code decimalValue().toPlainString()} rather than
     * {@code asText()} because Jackson strips trailing zeros when it parses a number
     * into a BigDecimal: {@code 150000.00} becomes {@code 1.5E+5}, and
     * {@code asText()} would put exactly that in front of a landlord. The scale is
     * pinned so every amount in a message is written the same way.
     */
    private static String money(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || node.isNull()) {
            return "-";
        }
        String amount = node.isNumber()
                ? node.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : node.asText();
        return amount + " " + text(payload, "currency", "");
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null || node.isNull() ? fallback : node.asText();
    }
}
