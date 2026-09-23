package io.github.pawanpandey407.pipeline.core;

import io.github.pawanpandey407.pipeline.model.Transaction;

/**
 * A check every newly arrived transaction passes before the stages run.
 *
 * Returning true lets the transaction through. Returning false means the
 * gate has taken custody of it, for example by holding it in quarantine,
 * and the pipeline must not process it now.
 */
public interface AdmissionGate {

    boolean admit(Transaction transaction);
}
