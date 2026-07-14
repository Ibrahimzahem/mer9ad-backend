package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.domain.Beneficiary;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.Transaction;

import java.util.List;

/**
 * The customer payload the frontend loads on startup: profile, balance, the payee list to pick
 * from, and the transaction history. Deliberately omits internal fields (knownPayeeIbans) that are
 * already expressed by each beneficiary's {@code saved} flag.
 */
public record CustomerView(
        String customerRef,
        String name,
        String iban,
        double balance,
        List<Beneficiary> beneficiaries,
        List<Transaction> transactions,
        String historySummary
) {
    public static CustomerView from(CustomerProfile p) {
        return new CustomerView(p.customerRef(), p.name(), p.iban(), p.verifiedBalance(),
                p.beneficiaries(), p.transactions(), p.historySummary());
    }
}
