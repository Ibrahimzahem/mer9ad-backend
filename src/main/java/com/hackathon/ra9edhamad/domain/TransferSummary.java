package com.hackathon.ra9edhamad.domain;

/**
 * The transfer the customer is attempting, as a compact grounding object for the pipeline and
 * the AI prompts.
 */
public record TransferSummary(
        String beneficiaryIban,
        double amount,
        boolean isNewBeneficiary,
        String purposeText,
        String purposeEntryMethod
) {
}
