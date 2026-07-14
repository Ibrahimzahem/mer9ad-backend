package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.aml.AmlClassifier;
import com.hackathon.ra9edhamad.aml.AmlFeatures;
import com.hackathon.ra9edhamad.domain.Account;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.FraudIntelRecord;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.Transaction;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tools the AML reasoning agent can call autonomously. Specialized for money-laundering
 * detection: structuring detection, transit analysis, round-tripping, the trained
 * ONNX classifier, and the fraud-intelligence log — different tools from the FraudAgent,
 * so the two agents genuinely investigate different things.
 */
public class AmlTool {

    private static final Logger log = LoggerFactory.getLogger(AmlTool.class);

    private static final double SAR_THRESHOLD = 10_000.0;

    private final AccountStore accountStore;
    private final AmlClassifier amlClassifier;
    private final FraudIntelStore fraudIntel;

    public AmlTool(AccountStore accountStore, AmlClassifier amlClassifier, FraudIntelStore fraudIntel) {
        this.accountStore = accountStore;
        this.amlClassifier = amlClassifier;
        this.fraudIntel = fraudIntel;
    }

    @Tool("Detect structuring / smurfing: count how many sub-threshold transfers " +
          "(below 10000) the customer has made to the same beneficiary ON THE SAME DAY. " +
          "3 or more on the same day is a strong structuring signal. " +
          "Transfers spread across different days are NOT structuring.")
    public String detectStructuring(String customerRef, String beneficiaryIban) {
        log.info("[AmlTool] detectStructuring({}, {})", customerRef, beneficiaryIban);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null || customer.transactions() == null) {
            return "No customer history available.";
        }
        // Group sub-threshold outgoing transfers by date
        Map<String, Integer> byDate = new java.util.HashMap<>();
        for (Transaction t : customer.transactions()) {
            if ("OUT".equals(t.direction()) && beneficiaryIban.equals(t.iban()) && t.amount() < SAR_THRESHOLD) {
                byDate.merge(t.date(), 1, Integer::sum);
            }
        }
        int maxSameDay = byDate.values().stream().max(Integer::compare).orElse(0);
        if (maxSameDay >= 3) {
            return String.format("STRUCTURING DETECTED: %d sub-threshold transfers to %s on the same day. " +
                    "This is a classic smurfing pattern to avoid SAR reporting threshold.", maxSameDay, beneficiaryIban);
        }
        return String.format("No structuring: max %d sub-threshold transfers on a single day to this beneficiary.", maxSameDay);
    }

    @Tool("Analyze the beneficiary account's transit velocity and age. " +
          "High transit velocity (≥0.4) + new account (≤30 days) = pass-through/mule risk. " +
          "Returns the account's risk profile.")
    public String analyzeAccountRisk(String iban) {
        log.info("[AmlTool] analyzeAccountRisk({})", iban);
        Account acc = accountStore.account(iban);
        if (acc == null) {
            return "Account not found. Unknown beneficiary, no derogatory record.";
        }
        String risk = "";
        if (acc.transitVelocity() >= 0.8) risk += "VERY HIGH transit velocity. ";
        else if (acc.transitVelocity() >= 0.4) risk += "Elevated transit velocity. ";
        if (acc.accountAgeDays() <= 30) risk += "New account. ";
        if (acc.muleWatchlistHit()) risk += "ON MULE WATCHLIST. ";
        if (risk.isEmpty()) risk = "Normal account profile.";
        return String.format("Account: %s | Age: %d days | Transit: %.2f | Mule: %s | Risk: %s",
                acc.holderName(), acc.accountAgeDays(), acc.transitVelocity(),
                acc.muleWatchlistHit() ? "YES" : "NO", risk);
    }

    @Tool("Check for round-tripping: has the customer received money FROM the same " +
          "IBAN they are now sending TO? A→B→A pattern indicates round-tripping.")
    public String checkRoundTripping(String customerRef, String beneficiaryIban) {
        log.info("[AmlTool] checkRoundTripping({}, {})", customerRef, beneficiaryIban);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null || customer.transactions() == null) {
            return "No customer history to check.";
        }
        boolean receivedFrom = customer.transactions().stream()
                .anyMatch(t -> "IN".equals(t.direction()) && beneficiaryIban.equals(t.iban()));
        if (receivedFrom) {
            return "ROUND-TRIPPING DETECTED: Customer has received money FROM this IBAN before. " +
                   "A→B→A pattern is a classic money-laundering indicator.";
        }
        return "No round-tripping pattern found.";
    }

    @Tool("Count the customer's cross-border / international beneficiaries. " +
          "Many international transfers to new accounts is a scattering risk.")
    public String countInternationalTransfers(String customerRef) {
        log.info("[AmlTool] countInternationalTransfers({})", customerRef);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null || customer.beneficiaries() == null) {
            return "No beneficiary data.";
        }
        long intlCount = customer.beneficiaries().stream()
                .filter(b -> b.name() != null && !isArabicName(b.name()))
                .count();
        if (intlCount >= 3) {
            return String.format("CROSS-BORDER RISK: %d international beneficiaries. " +
                    "High number of foreign accounts may indicate scattering.", intlCount);
        }
        return String.format("%d international beneficiaries — within normal range.", intlCount);
    }

    @Tool("Run the trained AML classifier (RandomForest, ONNX) on this transaction. " +
          "Uses 22 engineered features (transaction counts, amounts, ratios, velocity). " +
          "Returns a laundering probability 0-1. ≥0.75 is HIGH RISK. ≥0.40 is SUSPICIOUS. " +
          "This is the ML model trained on synthetic AML data — use it as a key signal.")
    public String runAmlClassifier(String customerRef, String beneficiaryIban, double amount) {
        log.info("[AmlTool] runAmlClassifier({}, {}, {})", customerRef, beneficiaryIban, amount);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null) return "Customer not found.";

        List<AmlFeatures.HistoryEntry> senderHistory = customer.transactions().stream()
                .filter(t -> "OUT".equals(t.direction()))
                .map(t -> new AmlFeatures.HistoryEntry(t.date(), t.iban(), t.amount(), t.category()))
                .collect(Collectors.toList());

        List<AmlFeatures.HistoryEntry> receiverHistory = customer.transactions().stream()
                .filter(t -> beneficiaryIban != null && beneficiaryIban.equals(t.iban()))
                .map(t -> new AmlFeatures.HistoryEntry(t.date(), t.iban(), t.amount(), t.category()))
                .collect(Collectors.toList());

        boolean receiverAlsoSends = customer.transactions().stream()
                .anyMatch(t -> "OUT".equals(t.direction()) && beneficiaryIban != null
                        && beneficiaryIban.equals(t.iban()));

        float[] features = AmlFeatures.compute(amount, senderHistory, receiverHistory, receiverAlsoSends, false);
        double score = amlClassifier.predictLaundering(features);

        if (score < 0) return "AML classifier not loaded. Use rule-based assessment.";
        String level = score >= 0.75 ? "HIGH RISK" : score >= 0.40 ? "SUSPICIOUS" : "LOW RISK";
        return String.format("Classifier score: %.2f (%s). Laundering probability: %.0f%%. " +
                "Model: RandomForest (22 features, ONNX). Trained on structuring/rapid-transit/round-tripping patterns.",
                score, level, score * 100);
    }

    @Tool("Get the customer's full transaction history summary — all outgoing transfers. " +
          "Use this to see patterns like repeated transfers to the same account, " +
          "sub-threshold amounts, or unusual frequency.")
    public String getTransactionHistory(String customerRef) {
        log.info("[AmlTool] getTransactionHistory({})", customerRef);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null || customer.transactions() == null) {
            return "No transaction history available.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Transaction history for ").append(customer.name()).append(":\n");
        for (Transaction t : customer.transactions()) {
            sb.append(String.format("  %s | %s | %s | %.0f SAR | %s\n",
                    t.date(), t.direction(), t.counterparty(), t.amount(), t.category()));
        }
        return sb.toString();
    }

    @Tool("Check the fraud-intelligence log for prior reports on this beneficiary IBAN. " +
          "If this account has been flagged in previous transfers (by this customer or others), " +
          "it strengthens the AML case. Returns the count and outcomes of prior flags.")
    public String checkFraudIntelLog(String iban) {
        log.info("[AmlTool] checkFraudIntelLog({})", iban);
        List<FraudIntelRecord> records = fraudIntel.forBeneficiary(iban);
        if (records.isEmpty()) {
            return "No prior fraud-intel records for this IBAN.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("FRAUD INTEL: %d prior record(s):\n", records.size()));
        for (FraudIntelRecord r : records) {
            sb.append(String.format("  • %s | %s | %s\n",
                    r.timestamp(), r.outcome(), r.detectedPattern() != null ? r.detectedPattern() : "n/a"));
        }
        return sb.toString();
    }

    private boolean isArabicName(String name) {
        if (name == null) return true;
        for (char c : name.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC) return true;
        }
        return false;
    }
}
