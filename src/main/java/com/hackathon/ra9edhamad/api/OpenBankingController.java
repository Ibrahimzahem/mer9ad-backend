package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.domain.Account;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.FraudIntelRecord;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Open Banking fraud-intelligence API. Participating banks contribute grey-zone
 * intel (risk signals + IBAN hash, no PII) and consume the aggregated reputation.
 * SAMA (the regulator) gets an aggregate view for national consumer-protection policy.
 *
 * <p>This is the Open Banking track hook + the sustainability/network-effect moat.
 * In production this runs behind OAuth2 with scope {@code fraud:intel:write}.
 */
@RestController
@RequestMapping("/open-banking/v1")
public class OpenBankingController {

    private final FraudIntelStore fraudIntel;
    private final ScamPlaybookStore playbook;
    private final AccountStore accountStore;

    public OpenBankingController(FraudIntelStore fraudIntel, ScamPlaybookStore playbook, AccountStore accountStore) {
        this.fraudIntel = fraudIntel;
        this.playbook = playbook;
        this.accountStore = accountStore;
    }

    /**
     * A contributing bank POSTs a grey-zone outcome to the network.
     * No PII — only risk signals + IBAN hash + the detected pattern.
     */
    @PostMapping("/fraud-intel")
    public Map<String, Object> contributeIntel(@RequestBody IntelContribution contribution) {
        // In production: validate OAuth2 token + scope, hash the IBAN, store in the consortium DB
        Map<String, Object> response = new HashMap<>();
        response.put("status", "accepted");
        response.put("contributingBank", contribution.contributingBank());
        response.put("ibanHash", hashIban(contribution.beneficiaryIban()));
        response.put("pattern", contribution.detectedPattern());
        response.put("message", "Contribution received. The network is now smarter.");
        return response;
    }

    /**
     * Query the aggregated reputation of a receiving account across the network.
     */
    @GetMapping("/accounts/{ibanHash}/reputation")
    public Map<String, Object> getReputation(@PathVariable String ibanHash) {
        Map<String, Object> rep = new HashMap<>();
        // In the prototype, check if the IBAN is a known mule in the seeded store
        // (in production this aggregates across all contributing banks)
        Account acc = findAccountByHash(ibanHash);
        if (acc != null && acc.muleWatchlistHit()) {
            rep.put("reputation", "FLAGED");
            rep.put("flags", List.of("MULE_WATCHLIST"));
            rep.put("contributingBanks", 1);
            rep.put("riskLevel", "HIGH");
        } else if (acc != null && acc.transitVelocity() >= 0.8) {
            rep.put("reputation", "SUSPICIOUS");
            rep.put("flags", List.of("HIGH_TRANSIT_VELOCITY"));
            rep.put("contributingBanks", 1);
            rep.put("riskLevel", "MEDIUM");
        } else {
            rep.put("reputation", "UNKNOWN");
            rep.put("flags", List.of());
            rep.put("contributingBanks", 0);
            rep.put("riskLevel", "LOW");
        }
        rep.put("ibanHash", ibanHash);
        return rep;
    }

    /**
     * The aggregate network view — for SAMA / the regulator.
     */
    @GetMapping("/network/overview")
    public Map<String, Object> networkOverview() {
        Map<String, Object> overview = new HashMap<>();
        List<FraudIntelRecord> allIntel = fraudIntel.all();
        long redCount = allIntel.stream().filter(r -> r.decision().name().equals("RED")).count();
        long orangeCount = allIntel.stream().filter(r -> r.decision().name().equals("ORANGE")).count();
        overview.put("totalIntelRecords", allIntel.size());
        overview.put("redFlags", redCount);
        overview.put("orangeInvestigations", orangeCount);
        overview.put("knownScamPatterns", playbook.patterns().size());
        overview.put("participatingBanks", 1); // prototype = 1; production = N
        overview.put("networkGrowth", "Every new bank makes every other bank smarter.");
        return overview;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String hashIban(String iban) {
        // In production: SHA-256 hash. In the prototype: return a masked IBAN.
        if (iban == null || iban.length() < 6) return "unknown";
        return iban.substring(0, 4) + "****" + iban.substring(iban.length() - 4);
    }

    private Account findAccountByHash(String ibanHash) {
        // In the prototype, the "hash" is the masked IBAN — look it up
        for (Account acc : accountStore.allAccounts()) {
            String masked = hashIban(acc.iban());
            if (masked.equals(ibanHash)) return acc;
        }
        return null;
    }

    public record IntelContribution(
            String contributingBank,
            String beneficiaryIban,
            String detectedPattern,
            String decision,
            double riskScore
    ) {
    }
}
