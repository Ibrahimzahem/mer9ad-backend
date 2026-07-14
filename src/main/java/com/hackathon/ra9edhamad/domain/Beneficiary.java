package com.hackathon.ra9edhamad.domain;

/**
 * A payee as the customer sees it in their app: a display name, the destination IBAN, the
 * customer's own label for the relationship, and whether it's a saved/trusted payee. This is the
 * customer-facing view only — it deliberately carries NO risk facts (account age, transit
 * velocity, mule flag stay server-side), so the beneficiary list never reveals which account is
 * a mule before the pipeline runs.
 */
public record Beneficiary(
        String name,
        String iban,
        String relationship, // customer's label: "صديق", "إيجار السكن", "متجر إلكتروني"...
        boolean saved        // true = in the customer's trusted/established list
) {
}
