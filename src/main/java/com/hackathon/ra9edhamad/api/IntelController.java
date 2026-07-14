package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.domain.Account;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.FraudIntelRecord;
import com.hackathon.ra9edhamad.domain.FraudIntelStore;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

/** Demo-visibility endpoints: the grey-zone intel feed, the growing playbook, the seeded data. */
@RestController
@RequestMapping("/api")
public class IntelController {

    private final FraudIntelStore fraudIntel;
    private final ScamPlaybookStore playbook;
    private final AccountStore accountStore;

    public IntelController(FraudIntelStore fraudIntel, ScamPlaybookStore playbook, AccountStore accountStore) {
        this.fraudIntel = fraudIntel;
        this.playbook = playbook;
        this.accountStore = accountStore;
    }

    @GetMapping("/fraud-intel")
    public List<FraudIntelRecord> fraudIntel() {
        return fraudIntel.all();
    }

    @GetMapping("/scam-playbook")
    public List<String> scamPlaybook() {
        return playbook.patterns();
    }

    @GetMapping("/accounts")
    public Collection<Account> accounts() {
        return accountStore.allAccounts();
    }
}
