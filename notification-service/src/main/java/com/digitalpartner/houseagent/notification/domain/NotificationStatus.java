package com.digitalpartner.houseagent.notification.domain;

/** How far a message has got towards the person it is for. */
public enum NotificationStatus {

    /** Composed and logged, not yet handed to SMTP. */
    PENDING,

    SENT,

    /** The last attempt failed. Still eligible for the retry sweep. */
    FAILED,

    /**
     * Retried until the limit and never delivered.
     *
     * <p>A terminal state so the sweep stops: a permanently bad address would
     * otherwise be retried forever, and the backlog it creates delays every message
     * queued behind it. The row stays as the record that someone was never told.
     */
    ABANDONED;

    public boolean isDeliverable() {
        return this == PENDING || this == FAILED;
    }
}
