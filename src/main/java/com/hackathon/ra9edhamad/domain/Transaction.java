package com.hackathon.ra9edhamad.domain;

/**
 * One line of the customer's transaction history. Seeded, static demo data used both to render a
 * realistic account statement in the UI and to ground the AI ("this customer has no history of
 * transfers to brand-new accounts").
 */
public record Transaction(
        String date,          // ISO date, e.g. "2026-06-25"
        String counterparty,  // the other party's display name
        String iban,          // the other party's IBAN (nullable)
        double amount,        // always positive; direction gives the sign
        String direction,     // "IN" | "OUT"
        String category       // "راتب", "إيجار", "تسوق", "تحويل شخصي"...
) {
}
