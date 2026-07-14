package com.hackathon.ra9edhamad.domain;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The grey-zone fraud-intelligence feedback log. */
@Component
public class FraudIntelStore {

    private final CopyOnWriteArrayList<FraudIntelRecord> records = new CopyOnWriteArrayList<>();

    public void add(FraudIntelRecord record) {
        records.add(record);
    }

    public List<FraudIntelRecord> all() {
        return List.copyOf(records);
    }

    /** Find all prior fraud-intel records for a given beneficiary IBAN. */
    public List<FraudIntelRecord> forBeneficiary(String iban) {
        if (iban == null) return List.of();
        return records.stream().filter(r -> iban.equals(r.beneficiaryIban())).toList();
    }

    /** Count how many times this IBAN was flagged across all records. */
    public int flagCount(String iban) {
        if (iban == null) return 0;
        return (int) records.stream().filter(r -> iban.equals(r.beneficiaryIban())).count();
    }
}
