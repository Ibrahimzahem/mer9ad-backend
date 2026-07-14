package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.domain.BehavioralSignals;
import com.hackathon.ra9edhamad.domain.CoercionSignals;
import com.hackathon.ra9edhamad.domain.TransferSummary;

/**
 * The transfer-intent payload (mirrors the documented shape). Untrusted: the pipeline recomputes
 * every server fact itself and never reads a client-supplied verdict.
 */
public record TransferIntentRequest(
        String eventId,
        String customerRef,
        Transfer transfer,
        Signals signals
) {
    public record Transfer(String beneficiaryIban, double amount, boolean isNewBeneficiary, Purpose purpose) {
    }

    public record Purpose(String text, String entryMethod) {
    }

    public record Signals(Behavioral behavioral, Coercion coercion) {
    }

    public record Behavioral(double cadenceDeviation, int cadenceSamples,
                             double deviceInconsistency, String baselineSource) {
    }

    public record Coercion(String ibanEntryMethod, long maxIdleGapMs, boolean activeCallDetected,
                           boolean remoteAccessAppDetected, String purposeEntryMethod,
                           long purposeFilledAfterIbanMs) {
    }

    // --- mapping to domain value objects ---

    public TransferSummary toTransferSummary() {
        String purposeText = transfer.purpose() != null ? transfer.purpose().text() : null;
        String purposeMethod = transfer.purpose() != null ? transfer.purpose().entryMethod() : null;
        return new TransferSummary(transfer.beneficiaryIban(), transfer.amount(),
                transfer.isNewBeneficiary(), purposeText, purposeMethod);
    }

    public BehavioralSignals toBehavioralSignals() {
        Behavioral b = signals != null ? signals.behavioral() : null;
        if (b == null) {
            return new BehavioralSignals(0, 0, 0, "unknown");
        }
        return new BehavioralSignals(b.cadenceDeviation(), b.cadenceSamples(), b.deviceInconsistency(), b.baselineSource());
    }

    public CoercionSignals toCoercionSignals() {
        Coercion c = signals != null ? signals.coercion() : null;
        if (c == null) {
            return new CoercionSignals("typed", 0, false, false, "typed", Long.MAX_VALUE);
        }
        return new CoercionSignals(c.ibanEntryMethod(), c.maxIdleGapMs(), c.activeCallDetected(),
                c.remoteAccessAppDetected(), c.purposeEntryMethod(), c.purposeFilledAfterIbanMs());
    }
}
