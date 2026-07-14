package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.aml.AmlClassifier;
import com.hackathon.ra9edhamad.aml.AmlFeatures;
import com.hackathon.ra9edhamad.domain.Account;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.FraudIntelRecord;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.Transaction;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tools the reasoning fraud agent can call autonomously. The LLM decides which tools
 * to use, in what order, based on what it finds — a genuine ReAct reasoning loop.
 *
 * <p>Each tool returns a String (the LLM reads the result and reasons about it).
 * The tools wrap the bank's real data stores: mule watchlist, account info,
 * transaction history, the trained AML classifier, the scam playbook, and the
 * fraud-intelligence log of prior intercepted transfers.
 */
public class FraudTool {

    private static final Logger log = LoggerFactory.getLogger(FraudTool.class);

    private final AccountStore accountStore;
    private final AmlClassifier amlClassifier;
    private final ScamPlaybookStore playbook;
    private final FraudIntelStore fraudIntel;

    public FraudTool(AccountStore accountStore, AmlClassifier amlClassifier,
                     ScamPlaybookStore playbook, FraudIntelStore fraudIntel) {
        this.accountStore = accountStore;
        this.amlClassifier = amlClassifier;
        this.playbook = playbook;
        this.fraudIntel = fraudIntel;
    }

    @Tool("Check if a beneficiary IBAN is on the mule watchlist. Returns YES or NO. " +
          "A mule account is a confirmed money-laundering pass-through account. " +
          "If YES, the transfer must be blocked immediately.")
    public String checkMuleWatchlist(String iban) {
        log.info("[FraudTool] checkMuleWatchlist({})", iban);
        Account acc = accountStore.account(iban);
        if (acc == null) {
            return "NO — account not found in the bank's records (unknown beneficiary).";
        }
        return acc.muleWatchlistHit()
                ? "YES — this IBAN is on the mule watchlist. This is a confirmed money-laundering account."
                : "NO — this IBAN is NOT on the mule watchlist.";
    }

    @Tool("Get server-verified info about a beneficiary account: age in days, transit velocity " +
          "(0-1, how fast money moves through it), and verified balance. " +
          "New accounts (≤30 days) with high transit velocity (≥0.4) are suspicious.")
    public String getAccountInfo(String iban) {
        log.info("[FraudTool] getAccountInfo({})", iban);
        Account acc = accountStore.account(iban);
        if (acc == null) {
            return "Account not found. Unknown beneficiary with no derogatory record. " +
                   "Treat as neutral: estimated age 365 days, transit velocity 0.15.";
        }
        return String.format(
                "Account holder: %s | Age: %d days | Transit velocity: %.2f | Balance: %.0f SAR | Mule: %s",
                acc.holderName(), acc.accountAgeDays(), acc.transitVelocity(),
                acc.verifiedBalance(), acc.muleWatchlistHit() ? "YES" : "NO");
    }

    @Tool("Get the paying customer's transaction history — their recent transfers. " +
          "Use this to check if they've ever transferred to this beneficiary before, " +
          "and to understand their normal banking patterns.")
    public String getCustomerHistory(String customerRef) {
        log.info("[FraudTool] getCustomerHistory({})", customerRef);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null) {
            return "Customer not found.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Customer: ").append(customer.name()).append("\n");
        sb.append("Balance: ").append(customer.verifiedBalance()).append(" SAR\n");
        sb.append("History summary: ").append(customer.historySummary()).append("\n");
        sb.append("Recent transactions:\n");
        if (customer.transactions() != null) {
            for (Transaction t : customer.transactions()) {
                sb.append(String.format("  %s | %s | %s | %.0f SAR | %s\n",
                        t.date(), t.direction(), t.counterparty(), t.amount(), t.category()));
            }
        }
        return sb.toString();
    }

    @Tool("Check if the customer has transferred to this beneficiary before (known payee). " +
          "Transfers to NEW beneficiaries the customer has never paid before are higher risk.")
    public String checkKnownPayee(String customerRef, String iban) {
        log.info("[FraudTool] checkKnownPayee({}, {})", customerRef, iban);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null) {
            return "Customer not found — cannot verify payee history.";
        }
        boolean known = customer.knowsPayee(iban);
        return known
                ? "YES — this is a KNOWN payee. The customer has transferred to this IBAN before."
                : "NO — this is a NEW payee. The customer has NEVER transferred to this IBAN before. Higher risk.";
    }

    @Tool("Score the money-laundering risk of this transfer using the trained AML classifier. " +
          "Returns a probability 0-1 that this transaction is laundering. " +
          "Scores ≥0.75 are high risk. Scores ≥0.40 are suspicious.")
    public String scoreAmlRisk(String customerRef, String beneficiaryIban, double amount) {
        log.info("[FraudTool] scoreAmlRisk({}, {}, {})", customerRef, beneficiaryIban, amount);
        CustomerProfile customer = accountStore.customer(customerRef);
        if (customer == null) {
            return "Cannot score — customer not found.";
        }

        // Build the feature vector from the customer's history
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

        if (score < 0) {
            return "AML classifier not loaded. Cannot score. Use rules-based assessment instead.";
        }

        String riskLevel = score >= 0.75 ? "HIGH RISK" : score >= 0.40 ? "SUSPICIOUS" : "LOW RISK";
        return String.format("AML classifier score: %.2f (%s). Probability of laundering: %.0f%%.",
                score, riskLevel, score * 100);
    }

    @Tool("Get the list of known scam patterns from the self-learning scam playbook. " +
          "Use this to check if the current transfer matches any known manipulation pattern. " +
          "The playbook grows every time Shield catches a new scam.")
    public String getKnownScamPatterns() {
        log.info("[FraudTool] getKnownScamPatterns()");
        List<String> patterns = playbook.patterns();
        if (patterns == null || patterns.isEmpty()) {
            return "No known scam patterns yet.";
        }
        return "Known scam patterns (" + patterns.size() + "):\n" +
                patterns.stream().map(p -> "• " + p).collect(Collectors.joining("\n"));
    }

    @Tool("Check the fraud-intelligence log for prior reports involving this beneficiary IBAN. " +
          "Returns how many times this IBAN was flagged before, and the outcomes (BLOCKED, CHALLENGE_ISSUED, etc.). " +
          "If this IBAN has been flagged multiple times, it is very likely a scam account.")
    public String checkFraudIntelHistory(String iban) {
        log.info("[FraudTool] checkFraudIntelHistory({})", iban);
        List<FraudIntelRecord> records = fraudIntel.forBeneficiary(iban);
        if (records.isEmpty()) {
            return "No prior fraud-intel records for this IBAN. It has never been flagged before.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("FRAUD INTEL: %d prior record(s) for this IBAN:\n", records.size()));
        for (FraudIntelRecord r : records) {
            sb.append(String.format("  • %s | outcome=%s | pattern=%s\n",
                    r.timestamp(), r.outcome(), r.detectedPattern() != null ? r.detectedPattern() : "unknown"));
        }
        return sb.toString();
    }
}
