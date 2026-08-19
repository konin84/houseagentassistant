package com.digitalpartner.houseagent.notification;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

/**
 * Makes Jackson parse every JSON number with a decimal point into a
 * {@code BigDecimal} rather than a {@code double}.
 *
 * <p>The same customisation payment-service applies, and for a reason that is even more
 * visible here. Amounts in an event are read straight into the body of an email, and a
 * double turns the {@code 150000.00} that was published into {@code 150000.0} on its
 * way to a landlord. Preserving the scale the sender chose is the difference between a
 * figure that looks like money and one that looks like a bug.
 */
@Singleton
public class JacksonMoneyConfig implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
