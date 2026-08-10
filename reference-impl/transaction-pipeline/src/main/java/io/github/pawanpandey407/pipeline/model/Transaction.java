package io.github.pawanpandey407.pipeline.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A synthetic payment transaction moving through the pipeline. */
public class Transaction {

    public enum Status {
        NEW,
        COMPLETED,
        FAILED
    }

    private final String id;
    private final String clientId;
    private final BigDecimal amount;
    private final Instant createdAt;

    private Status status = Status.NEW;

    /** Name of the stage that failed this transaction, null if none. */
    private String failedStage;

    public Transaction(String clientId, BigDecimal amount) {
        this.id = UUID.randomUUID().toString();
        this.clientId = clientId;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getFailedStage() {
        return failedStage;
    }

    public void setFailedStage(String failedStage) {
        this.failedStage = failedStage;
    }
}
