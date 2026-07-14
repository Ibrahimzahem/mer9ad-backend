package com.hackathon.ra9edhamad.domain;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the AI is allowed to reason over for one transfer: the untrusted client signals
 * (kept in their two families), the server-verified facts, the transfer summary, and the
 * currently known manipulation patterns. The AI must reason ONLY over this — it must not
 * invent facts.
 */
public record GroundedContext(
        String customerRef,
        TransferSummary transfer,
        BehavioralSignals behavioral,
        CoercionSignals coercion,
        ServerFacts serverFacts,
        List<String> knownPatterns
) {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Compact JSON grounding block embedded in the AI user message. */
    public String toCompactJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("customerRef", customerRef);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("beneficiaryIban", transfer.beneficiaryIban());
        t.put("amount", transfer.amount());
        t.put("isNewBeneficiary", transfer.isNewBeneficiary());
        t.put("purposeText", transfer.purposeText());
        t.put("purposeEntryMethod", transfer.purposeEntryMethod());
        root.put("transfer", t);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("cadenceDeviation", behavioral.cadenceDeviation());
        b.put("deviceInconsistency", behavioral.deviceInconsistency());
        b.put("baselineSource", behavioral.baselineSource());
        root.put("clientBehavioralSignals", b);

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("ibanEntryMethod", coercion.ibanEntryMethod());
        c.put("maxIdleGapMs", coercion.maxIdleGapMs());
        c.put("activeCallDetected", coercion.activeCallDetected());
        c.put("remoteAccessAppDetected", coercion.remoteAccessAppDetected());
        c.put("purposeEntryMethod", coercion.purposeEntryMethod());
        c.put("purposeFilledAfterIbanMs", coercion.purposeFilledAfterIbanMs());
        root.put("clientCoercionSignals", c);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("beneficiaryAccountAgeDays", serverFacts.accountAgeDays());
        f.put("beneficiaryTransitVelocity", serverFacts.transitVelocity());
        f.put("muleWatchlistHit", serverFacts.muleWatchlistHit());
        f.put("beneficiaryInCustomerHistory", serverFacts.isKnownPayee());
        f.put("customerHistorySummary", serverFacts.historySummary());
        root.put("serverVerifiedFacts", f);

        root.put("knownManipulationPatterns", knownPatterns);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
}
