package com.hackathon.ra9edhamad.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** The flat signals snapshot embedded in a {@link FraudIntelRecord} — shared by every reporting path. */
public final class SignalsSnapshot {

    private SignalsSnapshot() {
    }

    public static Map<String, Object> of(TransferSummary transfer, BehavioralSignals behavioral,
                                          CoercionSignals coercion, ServerFacts facts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", transfer.amount());
        m.put("isNewBeneficiary", transfer.isNewBeneficiary());
        m.put("purposeEntryMethod", transfer.purposeEntryMethod());
        m.put("ibanEntryMethod", coercion.ibanEntryMethod());
        m.put("activeCallDetected", coercion.activeCallDetected());
        m.put("remoteAccessAppDetected", coercion.remoteAccessAppDetected());
        m.put("maxIdleGapMs", coercion.maxIdleGapMs());
        m.put("behavioralScore", behavioral.score());
        m.put("beneficiaryAccountAgeDays", facts.accountAgeDays());
        m.put("beneficiaryTransitVelocity", facts.transitVelocity());
        m.put("muleWatchlistHit", facts.muleWatchlistHit());
        return m;
    }
}
