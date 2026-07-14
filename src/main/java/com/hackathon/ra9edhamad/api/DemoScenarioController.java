package com.hackathon.ra9edhamad.api;

import com.hackathon.ra9edhamad.domain.AccountStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Pre-built demo scenarios for the hackathon pitch. Each scenario is a complete
 * transfer-intent request body that triggers a specific agent path:
 *
 * <ul>
 *   <li><b>SCAM</b> — coercion signals + new high-transit account → FraudAgent ORANGE → intervention</li>
 *   <li><b>MULE</b> — confirmed mule watchlist hit → FraudAgent RED (hard fact)</li>
 *   <li><b>STRUCTURING</b> — 4 sub-threshold transfers → AmlAgent RED (ML + rules)</li>
 *   <li><b>CLEAN</b> — known payee, established account → GREEN</li>
 * </ul>
 *
 * GET  /api/demo/scenarios — list all scenarios
 * POST /api/demo/scenarios/{id} — get the request body for a scenario (to POST to /api/transfer-intent)
 */
@RestController
@RequestMapping("/api/demo")
public class DemoScenarioController {

    @GetMapping("/scenarios")
    public List<Map<String, String>> scenarios() {
        return List.of(
                Map.of("id", "scam", "name", "احتيال هاتفي (Social Engineering)",
                        "description", "مكالمة نشطة + مستفيد جديد + حساب سريع العبور → تدخل الوكيل",
                        "expected", "ORANGE → intervention chat"),
                Map.of("id", "mule", "name", "حساب تمرير أموال (Mule)",
                        "description", "حساب مؤكد في قائمة المول → إيقاف فوري",
                        "expected", "RED (hard fact)"),
                Map.of("id", "structuring", "name", "تجزئة المعاملات (Structuring/Smurfing)",
                        "description", "4 تحويلات تحت الحد لنفس المستفيد → AML RED",
                        "expected", "RED (AML + classifier)"),
                Map.of("id", "clean", "name", "تحويل عادي (Clean)",
                        "description", "مستفيد معتمد، حساب قديم → موافقة",
                        "expected", "GREEN")
        );
    }

    @GetMapping("/scenarios/{id}")
    public TransferIntentRequest scenario(@PathVariable String id) {
        return switch (id.toLowerCase()) {
            case "scam" -> scamScenario();
            case "mule" -> muleScenario();
            case "structuring" -> structuringScenario();
            case "clean" -> cleanScenario();
            default -> throw new IllegalArgumentException("Unknown scenario: " + id);
        };
    }

    // SCAM: active call + new high-transit account → ORANGE → intervention
    private TransferIntentRequest scamScenario() {
        return new TransferIntentRequest(
                "evt_scam_demo",
                AccountStore.DEMO_CUSTOMER,
                new TransferIntentRequest.Transfer(
                        AccountStore.IBAN_NEW_HIGH_TRANSIT, 5000, true,
                        new TransferIntentRequest.Purpose("مساعدة عاجلة", "paste")),
                new TransferIntentRequest.Signals(
                        new TransferIntentRequest.Behavioral(0.85, 12, 0.6, "30day_baseline"),
                        new TransferIntentRequest.Coercion("paste", 8000, true, true,
                                "voice_to_text", 1200))
        );
    }

    // MULE: confirmed mule account → RED hard fact
    private TransferIntentRequest muleScenario() {
        return new TransferIntentRequest(
                "evt_mule_demo",
                AccountStore.DEMO_CUSTOMER,
                new TransferIntentRequest.Transfer(
                        AccountStore.IBAN_MULE, 7000, true,
                        new TransferIntentRequest.Purpose("استثمار", "typed")),
                new TransferIntentRequest.Signals(
                        new TransferIntentRequest.Behavioral(0.5, 8, 0.2, "30day_baseline"),
                        new TransferIntentRequest.Coercion("typed", 5000, false, false,
                                "typed", 30000))
        );
    }

    // STRUCTURING: AML red — 4 sub-threshold transfers already in history
    private TransferIntentRequest structuringScenario() {
        return new TransferIntentRequest(
                "evt_structuring_demo",
                AccountStore.DEMO_CUSTOMER,
                new TransferIntentRequest.Transfer(
                        AccountStore.IBAN_STRUCTURING, 9000, false,
                        new TransferIntentRequest.Purpose("دفعات", "typed")),
                new TransferIntentRequest.Signals(
                        new TransferIntentRequest.Behavioral(0.1, 30, 0.0, "30day_baseline"),
                        new TransferIntentRequest.Coercion("typed", 0, false, false,
                                "typed", Long.MAX_VALUE))
        );
    }

    // CLEAN: known payee, established account → GREEN
    private TransferIntentRequest cleanScenario() {
        return new TransferIntentRequest(
                "evt_clean_demo",
                AccountStore.DEMO_CUSTOMER,
                new TransferIntentRequest.Transfer(
                        AccountStore.IBAN_ESTABLISHED, 500, false,
                        new TransferIntentRequest.Purpose("تحويل شخصي", "typed")),
                new TransferIntentRequest.Signals(
                        new TransferIntentRequest.Behavioral(0.05, 30, 0.0, "30day_baseline"),
                        new TransferIntentRequest.Coercion("typed", 0, false, false,
                                "typed", Long.MAX_VALUE))
        );
    }
}
