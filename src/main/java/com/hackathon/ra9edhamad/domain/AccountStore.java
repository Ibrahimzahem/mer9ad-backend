package com.hackathon.ra9edhamad.domain;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, seeded "bank" data: beneficiary accounts and the customer profile. Stands in for the
 * server-verified source of truth (the client never sends any of this). Seeded at startup with one
 * static demo customer, a set of named beneficiaries spanning every outcome (GREEN / ORANGE / RED),
 * and a short transaction history.
 */
@Component
public class AccountStore {

    public static final String DEMO_CUSTOMER = "cust_8842";

    // Beneficiary IBANs (SA + 22 digits). Names/labels live on the Account + Beneficiary records.
    public static final String IBAN_SALARY = "SA0000000000000000000000";           // employer, incoming
    public static final String IBAN_ESTABLISHED = "SA0000000000000000001111";       // trusted friend -> GREEN
    public static final String IBAN_NEW_HIGH_TRANSIT = "SA0000000000000000002222";  // new, grey -> ORANGE
    public static final String IBAN_MULE = "SA0000000000000000003333";              // confirmed mule -> RED
    public static final String IBAN_RENT = "SA0000000000000000004444";              // landlord -> GREEN
    public static final String IBAN_MERCHANT = "SA0000000000000000005555";          // online store -> GREEN
    public static final String IBAN_NEW_FRIEND = "SA0000000000000000006666";        // new friend, grey -> ORANGE
    public static final String IBAN_MULE_2 = "SA0000000000000000007777";            // confirmed mule -> RED
    public static final String IBAN_INTERNATIONAL = "SA0000000000000000008888";     // unusual intl -> ORANGE
    public static final String IBAN_STRUCTURING = "SA0000000000000000009999";      // structuring pattern -> AML RED

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CustomerProfile> customers = new ConcurrentHashMap<>();

    public AccountStore() {
        seed();
    }

    private void seed() {
        // --- Beneficiary accounts (server-verified risk facts) ---
        // iban, holderName, ageDays, transitVelocity, muleWatchlistHit, verifiedBalance
        put(new Account(IBAN_SALARY, "شركة الرياض للتقنية", 1200, 0.05, false, 999_999));
        put(new Account(IBAN_ESTABLISHED, "خالد العتيبي", 800, 0.10, false, 50_000));       // -> GREEN
        put(new Account(IBAN_RENT, "عبدالله المطيري", 600, 0.08, false, 20_000));           // -> GREEN
        put(new Account(IBAN_MERCHANT, "متجر سلة", 300, 0.20, false, 80_000));              // -> GREEN
        put(new Account(IBAN_NEW_FRIEND, "سعد الدوسري", 20, 0.50, false, 3_000));           // -> ORANGE (clearable)
        put(new Account(IBAN_INTERNATIONAL, "Ahmed Trading LLC", 15, 0.45, false, 2_000));  // -> ORANGE
        put(new Account(IBAN_NEW_HIGH_TRANSIT, "عبدالعزيز الشمري", 3, 0.85, false, 1_200));  // -> ORANGE (coached -> RED)
        put(new Account(IBAN_MULE, "سلطان الغامدي", 10, 0.90, true, 4_000));                // -> RED (mule)
        put(new Account(IBAN_MULE_2, "ماجد القحطاني", 5, 0.95, true, 6_000));               // -> RED (mule)
        put(new Account(IBAN_STRUCTURING, "مؤسسة الفجر التجارية", 8, 0.88, false, 12_000)); // -> AML RED (structuring)

        // --- The static demo customer ---
        // Saved/trusted payees the customer has transacted with before (drives isKnownPayee grounding).
        Set<String> knownPayees = new LinkedHashSet<>(List.of(
                IBAN_SALARY, IBAN_ESTABLISHED, IBAN_RENT, IBAN_MERCHANT));

        // Customer-facing payee list (no risk facts). Saved = trusted; unsaved = new/ad-hoc payees.
        List<Beneficiary> beneficiaries = List.of(
                new Beneficiary("خالد العتيبي", IBAN_ESTABLISHED, "صديق", true),
                new Beneficiary("عبدالله المطيري", IBAN_RENT, "إيجار السكن", true),
                new Beneficiary("متجر سلة", IBAN_MERCHANT, "متجر إلكتروني", true),
                new Beneficiary("شركة الرياض للتقنية", IBAN_SALARY, "جهة العمل", true),
                new Beneficiary("سعد الدوسري", IBAN_NEW_FRIEND, "صديق (جديد)", false),
                new Beneficiary("عبدالعزيز الشمري", IBAN_NEW_HIGH_TRANSIT, "مستفيد جديد", false),
                new Beneficiary("سلطان الغامدي", IBAN_MULE, "مستفيد جديد", false),
                new Beneficiary("ماجد القحطاني", IBAN_MULE_2, "مستفيد جديد", false),
                new Beneficiary("Ahmed Trading LLC", IBAN_INTERNATIONAL, "تحويل دولي", false),
                new Beneficiary("مؤسسة الفجر التجارية", IBAN_STRUCTURING, "مؤسسة تجارية", false));

        // Recent statement — all outgoing transfers go to trusted payees; none to a brand-new account.
        // Plus: 4 sub-threshold transfers to IBAN_STRUCTURING (the AML structuring demo scenario).
        List<Transaction> transactions = List.of(
                new Transaction("2026-06-01", "شركة الرياض للتقنية", IBAN_SALARY, 9_000.0, "IN", "راتب"),
                new Transaction("2026-06-02", "عبدالله المطيري", IBAN_RENT, 2_500.0, "OUT", "إيجار"),
                new Transaction("2026-06-05", "متجر سلة", IBAN_MERCHANT, 260.0, "OUT", "تسوق"),
                new Transaction("2026-06-11", "خالد العتيبي", IBAN_ESTABLISHED, 500.0, "OUT", "تحويل شخصي"),
                new Transaction("2026-06-17", "متجر سلة", IBAN_MERCHANT, 180.0, "OUT", "تسوق"),
                new Transaction("2026-06-22", "متجر سلة", IBAN_MERCHANT, 95.0, "OUT", "تسوق"),
                new Transaction("2026-06-28", "خالد العتيبي", IBAN_ESTABLISHED, 300.0, "OUT", "تحويل شخصي"),
                new Transaction("2026-06-30", "متجر سلة", IBAN_MERCHANT, 210.0, "OUT", "تسوق"),
                // AML structuring scenario: 4 sub-threshold transfers to the same beneficiary in one day
                new Transaction("2026-07-10", "مؤسسة الفجر التجارية", IBAN_STRUCTURING, 8_500.0, "OUT", "دفعات"),
                new Transaction("2026-07-10", "مؤسسة الفجر التجارية", IBAN_STRUCTURING, 7_200.0, "OUT", "دفعات"),
                new Transaction("2026-07-10", "مؤسسة الفجر التجارية", IBAN_STRUCTURING, 6_800.0, "OUT", "دفعات"),
                new Transaction("2026-07-10", "مؤسسة الفجر التجارية", IBAN_STRUCTURING, 5_500.0, "OUT", "دفعات"));

        customers.put(DEMO_CUSTOMER, new CustomerProfile(
                DEMO_CUSTOMER,
                "ياسر العوفي",
                "SA0000000000000000008842",
                8_500.0,
                knownPayees,
                beneficiaries,
                transactions,
                "العميل لديه راتب شهري ومستفيدون معتمدون سابقون؛ لا يوجد سجل تحويلات لحسابات حديثة."
        ));
    }

    private void put(Account a) {
        accounts.put(a.iban(), a);
    }

    public Account account(String iban) {
        return accounts.get(iban);
    }

    public CustomerProfile customer(String customerRef) {
        return customers.get(customerRef);
    }

    /** The single static demo customer (cust_8842). */
    public CustomerProfile demoCustomer() {
        return customers.get(DEMO_CUSTOMER);
    }

    public Collection<Account> allAccounts() {
        return accounts.values();
    }
}
