package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.domain.BehavioralSignals;
import com.hackathon.ra9edhamad.domain.CoercionSignals;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import com.hackathon.ra9edhamad.domain.TransferSummary;

import java.util.List;

/**
 * Everything a specialist agent is allowed to see for one transfer assessment.
 * This is the agent-system equivalent of {@code GroundedContext} — agents reason
 * only over what's in here, never inventing facts.
 */
public record AgentContext(
        String eventId,
        String customerRef,
        TransferSummary transfer,
        BehavioralSignals behavioral,
        CoercionSignals coercion,
        ServerFacts serverFacts,
        CustomerProfile customer,
        List<String> knownScamPatterns,
        List<TransactionHistoryEntry> transactionHistory
) {
    /**
     * One entry in the customer/beneficiary transaction history, flattened for agents
     * that don't need the full domain model.
     */
    public record TransactionHistoryEntry(
            String date,
            String counterpartyIban,
            double amount,
            String direction,     // "IN" | "OUT"
            String paymentFormat  // "ACH" | "CHECK" | "WIRE" | etc. (mapped from category)
    ) {
    }
}
