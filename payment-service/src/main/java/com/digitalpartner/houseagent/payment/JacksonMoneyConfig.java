package com.digitalpartner.houseagent.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Makes Jackson parse every JSON number with a decimal point into a
 * {@code BigDecimal} rather than a {@code double}.
 *
 * <p>By default a rent amount arriving in a lease event is parsed as a double and only
 * then converted, so the value that reaches the database has already been through
 * binary floating point. For values of this size that happens to be exact, which is
 * precisely what makes it dangerous: it works until an amount, a currency with three
 * minor units, or a percentage split makes it not work, and then the discrepancy shows
 * up as a payout that is a centime short with no obvious cause.
 *
 * <p>This is a payment service. The one place it must never take a shortcut is the
 * representation of money.
 */
@Singleton
public class JacksonMoneyConfig implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
