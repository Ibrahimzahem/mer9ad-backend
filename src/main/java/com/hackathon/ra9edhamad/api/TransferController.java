package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.agent.AgentContext;
import com.hackathon.ra9edhamad.agent.MasterAgent;
import com.hackathon.ra9edhamad.domain.Account;
import com.hackathon.ra9edhamad.domain.AccountStore;
import com.hackathon.ra9edhamad.domain.CustomerProfile;
import com.hackathon.ra9edhamad.domain.IbanValidator;
import com.hackathon.ra9edhamad.domain.ScamPlaybookStore;
import com.hackathon.ra9edhamad.domain.ServerFacts;
import com.hackathon.ra9edhamad.domain.Transaction;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final MasterAgent masterAgent;
    private final AccountStore accountStore;
    private final ScamPlaybookStore playbook;

    public TransferController(MasterAgent masterAgent, AccountStore accountStore, ScamPlaybookStore playbook) {
        this.masterAgent = masterAgent;
        this.accountStore = accountStore;
        this.playbook = playbook;
    }

    @PostMapping("/transfer-intent")
    public TransferIntentResponse transferIntent(@RequestBody TransferIntentRequest req) {
        String beneficiaryIban = req.transfer() != null ? req.transfer().beneficiaryIban() : null;
        if (!IbanValidator.isValid(beneficiaryIban)) {
            throw new InvalidIbanException(beneficiaryIban);
        }

        // Build the agent context (server-side facts, customer profile, transaction history)
        ServerFacts facts = resolveFacts(req.customerRef(), req.toTransferSummary());
        CustomerProfile customer = accountStore.customer(req.customerRef());
        List<AgentContext.TransactionHistoryEntry> history = buildHistory(customer);

        AgentContext ctx = new AgentContext(
                req.eventId() != null ? req.eventId() : "evt_" + System.currentTimeMillis(),
                req.customerRef(),
                req.toTransferSummary(),
                req.toBehavioralSignals(),
                req.toCoercionSignals(),
                facts,
                customer,
                playbook.patterns(),
                history
        );

        MasterAgent.MasterResult result = masterAgent.orchestrate(ctx);
        return TransferIntentResponse.from(result);
    }

    @PostMapping("/intervention/{sessionId}/reply")
    public ReplyResponse reply(@PathVariable String sessionId, @RequestBody ReplyRequest req) {
        MasterAgent.MasterReplyResult result = masterAgent.reply(sessionId, req.answer());
        return ReplyResponse.from(result);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private ServerFacts resolveFacts(String customerRef, com.hackathon.ra9edhamad.domain.TransferSummary transfer) {
        CustomerProfile customer = accountStore.customer(customerRef);
        double balance = customer != null ? customer.verifiedBalance() : 0.0;
        String history = customer != null ? customer.historySummary() : "لا يوجد سجل متاح لهذا العميل.";
        boolean known = customer != null && customer.knowsPayee(transfer.beneficiaryIban());

        Account acc = accountStore.account(transfer.beneficiaryIban());
        if (acc == null) {
            return new ServerFacts(transfer.beneficiaryIban(), 365, 0.15, false, balance, known, history);
        }
        return new ServerFacts(transfer.beneficiaryIban(), acc.accountAgeDays(), acc.transitVelocity(),
                acc.muleWatchlistHit(), balance, known, history);
    }

    private List<AgentContext.TransactionHistoryEntry> buildHistory(CustomerProfile customer) {
        if (customer == null || customer.transactions() == null) return List.of();
        return customer.transactions().stream()
                .map(t -> new AgentContext.TransactionHistoryEntry(
                        t.date(), t.iban(), t.amount(), t.direction(), mapPaymentFormat(t.category())))
                .toList();
    }

    private String mapPaymentFormat(String category) {
        if (category == null) return "ACH";
        return switch (category) {
            case "راتب" -> "WIRE";
            case "إيجار" -> "ACH";
            case "تسوق" -> "CARD";
            default -> "ACH";
        };
    }
}
