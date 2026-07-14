package com.hackathon.ra9edhamad.domain;

import java.util.List;
import java.util.Set;

/**
 * The paying customer as the bank knows them. {@code knownPayeeIbans} is the customer's history of
 * established beneficiaries (salary payee + previously trusted payees); {@code historySummary} is a
 * short Arabic grounding string handed to the AI. {@code beneficiaries} and {@code transactions}
 * are the static, demo-facing data the frontend renders (payee list + account statement).
 */
public record CustomerProfile(
        String customerRef,
        String name,
        String iban,
        double verifiedBalance,
        Set<String> knownPayeeIbans,
        List<Beneficiary> beneficiaries,
        List<Transaction> transactions,
        String historySummary
) {
    public boolean knowsPayee(String iban) {
        return knownPayeeIbans.contains(iban);
    }
}
