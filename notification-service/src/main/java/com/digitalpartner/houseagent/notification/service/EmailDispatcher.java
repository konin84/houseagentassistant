package com.digitalpartner.houseagent.notification.service;

import com.digitalpartner.houseagent.notification.domain.Notification;
import com.digitalpartner.houseagent.notification.domain.NotificationStatus;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Hands logged messages to SMTP, and keeps trying the ones that did not get through.
 *
 * <h2>Why sending is separate from composing</h2>
 *
 * SMTP is somebody else's server and it will be slow or down at some point. If that
 * failed the Kafka consumer, the payment event would be redelivered and recomposed
 * forever; if it were swallowed, the landlord would never learn they had been paid and
 * nothing would record that.
 *
 * <p>So the message is committed to the delivery log first and sent afterwards. A
 * failure leaves a row saying what should have gone out, and this sweep retries it.
 */
@ApplicationScoped
public class EmailDispatcher {

    private static final Logger LOG = Logger.getLogger(EmailDispatcher.class);

    /** Bounded so one sweep cannot pull an unbounded backlog into memory. */
    private static final int BATCH_SIZE = 50;

    @Inject
    Mailer mailer;

    /**
     * After this many failures a message is abandoned rather than retried forever.
     *
     * <p>A permanently bad address is not a transient failure, and retrying it
     * indefinitely delays every message queued behind it.
     */
    @ConfigProperty(name = "app.notification.max-attempts", defaultValue = "5")
    int maxAttempts;

    @Scheduled(
            every = "${app.notification.retry-interval:5m}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledRetry() {
        int sent = deliverPending();
        if (sent > 0) {
            LOG.infof("Delivered %d previously undelivered notification(s)", sent);
        }
    }

    /**
     * Attempts every message not yet delivered, returning how many got through.
     *
     * <p>Public and separate from the schedule so tests can drive it explicitly rather
     * than waiting on a timer.
     */
    public int deliverPending() {
        List<UUID> undelivered = QuarkusTransaction.requiringNew().call(() ->
                Notification.<Notification>find(
                                "status in ?1",
                                Sort.by("createdAt"),
                                List.of(NotificationStatus.PENDING, NotificationStatus.FAILED))
                        .page(0, BATCH_SIZE)
                        .list()
                        .stream()
                        .map(n -> n.id)
                        .toList());

        int sent = 0;
        for (UUID id : undelivered) {
            if (deliver(id)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Sends one message, recording the outcome either way.
     *
     * <p>Each in its own transaction: one bad address must not roll back the successful
     * deliveries beside it, and a status that was written must survive whatever the
     * next message does.
     */
    public boolean deliver(UUID notificationId) {
        Notification notification = QuarkusTransaction.requiringNew()
                .call(() -> Notification.<Notification>findById(notificationId));

        if (notification == null || !notification.status.isDeliverable()) {
            return false;
        }

        try {
            mailer.send(Mail.withText(
                    notification.recipient, notification.subject, notification.body));

            QuarkusTransaction.requiringNew().run(() -> {
                Notification fresh = Notification.findById(notificationId);
                if (fresh != null) {
                    fresh.markSent();
                }
            });
            return true;

        } catch (Exception e) {
            LOG.warnf(e, "Could not deliver notification %s to %s; will retry",
                    notificationId, notification.recipient);

            QuarkusTransaction.requiringNew().run(() -> {
                Notification fresh = Notification.findById(notificationId);
                if (fresh != null) {
                    fresh.markFailed(e.getMessage(), maxAttempts);
                }
            });
            return false;
        }
    }
}
