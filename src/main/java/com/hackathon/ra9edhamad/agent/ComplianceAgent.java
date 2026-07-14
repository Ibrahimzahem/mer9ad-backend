package com.hackathon.ra9edhamad.agent;

import com.hackathon.ra9edhamad.domain.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compliance specialist agent. When the MasterAgent fuses a RED verdict (from Fraud
 * or AML), the ComplianceAgent drafts a Suspicious Activity Report (SAR) for the bank's
 * compliance team — a structured document with the transaction details, the flagged
 * patterns, and an Arabic narrative summary.
 *
 * <p>In production this would integrate with the bank's case-management system and
 * SAMA's reporting portal. In the prototype it returns a structured JSON draft that
 * the frontend can render and the compliance team can review.
 *
 * <p>This agent does NOT use an LLM in the prototype — the narrative is template-based
 * (deterministic, fast, reliable). If you add the LLM later, it would enrich the
 * narrative field only.
 */
@Component
public class ComplianceAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ComplianceAgent.class);

    @Override
    public String name() {
        return "ComplianceAgent";
    }

    @Override
    public AgentVerdict assess(AgentContext ctx) {
        // The ComplianceAgent doesn't make RED/ORANGE/GREEN decisions — it only activates
        // AFTER a RED is fused by the MasterAgent. In the agent loop it always returns GREEN
        // (it's not a detector). The MasterAgent calls draftSar() separately when needed.
        return AgentVerdict.green("وكالة الامتثال جاهزة لإصدار البلاغات.", "COMPLIANCE_READY");
    }

    /**
     * Draft a SAR (Suspicious Activity Report) for a blocked transaction.
     * Called by the MasterAgent (or a controller) when the fused decision is RED.
     *
     * @param ctx       the agent context for the blocked transfer
     * @param verdicts  all specialist agent verdicts (for the "factors" section)
     * @param reportRef the report reference assigned by the MasterAgent
     * @return a structured SAR draft
     */
    public SarDraft draftSar(AgentContext ctx, List<AgentVerdict> verdicts, String reportRef) {
        log.info("[ComplianceAgent] drafting SAR {} for customer={} iban={}",
                reportRef, ctx.customerRef(), ctx.transfer().beneficiaryIban());

        // Collect the factors from each agent that fired
        List<Map<String, Object>> factors = verdicts.stream()
                .filter(v -> v.decision() != Decision.GREEN)
                .map(v -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("agent", inferAgentName(v));
                    f.put("decision", v.decision().name());
                    f.put("score", v.score());
                    f.put("ruleId", v.ruleId());
                    f.put("evidence", v.evidence());
                    f.put("hardFact", v.fromHardFact());
                    return f;
                })
                .toList();

        String narrative = buildNarrative(ctx, verdicts);

        SarDraft draft = new SarDraft(
                reportRef,
                "SAR-" + reportRef,
                Instant.now().toString(),
                ctx.customerRef(),
                ctx.transfer().beneficiaryIban(),
                ctx.transfer().amount(),
                ctx.serverFacts().muleWatchlistHit(),
                ctx.serverFacts().accountAgeDays(),
                ctx.serverFacts().transitVelocity(),
                factors,
                narrative,
                "DRAFT"  // status: draft → reviewed → filed
        );

        log.info("[ComplianceAgent] SAR draft {} complete: {} factors, narrative length={}",
                reportRef, factors.size(), narrative.length());

        return draft;
    }

    private String buildNarrative(AgentContext ctx, List<AgentVerdict> verdicts) {
        StringBuilder sb = new StringBuilder();

        sb.append("بلاغ نشاط مشبوه (SAR)\n\n");
        sb.append("التاريخ: ").append(Instant.now().toString()).append("\n");
        sb.append("العميل: ").append(ctx.customerRef()).append("\n");
        sb.append("الحساب المستفيد: ").append(ctx.transfer().beneficiaryIban()).append("\n");
        sb.append("المبلغ: ").append(ctx.transfer().amount()).append(" ريال سعودي\n\n");

        sb.append("الملخص:\n");
        if (ctx.serverFacts().muleWatchlistHit()) {
            sb.append("تم إيقاف التحويل بسبب أن الحساب المستفيد مُدرج في قائمة حسابات ")
              .append("تمرير الأموال (mule watchlist). هذا قرار قطعي مبني على حقائق موثقة من الخادم.\n\n");
        }

        sb.append("العوامل المُكتشفة:\n");
        for (AgentVerdict v : verdicts) {
            if (v.decision() != Decision.GREEN) {
                String agentName = inferAgentName(v);
                sb.append("• ").append(agentName).append(": ").append(v.evidence()).append("\n");
            }
        }

        sb.append("\nالتوصية: ");
        if (ctx.serverFacts().muleWatchlistHit()) {
            sb.append("إيقاف التحويل فورًا والإبلاغ لإدارة مكافحة غسيل الأموال والجهات التنظيمية.");
        } else {
            sb.append("تعليق التحويل pending مراجعة فريق الامتثال وتقديم بلاغ رسمي لساما.");
        }

        return sb.toString();
    }

    private String inferAgentName(AgentVerdict v) {
        if (v.ruleId() == null) return "Agent";
        if (v.ruleId().startsWith("AML") || v.ruleId().contains("STRUCTURING")
                || v.ruleId().contains("TRANSIT") || v.ruleId().contains("ROUND_TRIP")) {
            return "AmlAgent";
        }
        if (v.ruleId().startsWith("FRAUD") || v.ruleId().contains("MULE")) {
            return "FraudAgent";
        }
        return "Agent";
    }

    /**
     * A structured SAR draft. The frontend can render this; the compliance team reviews it
     * before filing to SAMA.
     */
    public record SarDraft(
            String reportReference,       // RPT-XXXX (from MasterAgent)
            String sarId,                 // SAR-RPT-XXXX
            String timestamp,             // ISO instant
            String customerRef,
            String beneficiaryIban,
            double amount,
            boolean muleWatchlistHit,
            int beneficiaryAccountAgeDays,
            double beneficiaryTransitVelocity,
            List<Map<String, Object>> factors,  // per-agent findings
            String narrative,             // Arabic SAR narrative
            String status                 // DRAFT | REVIEWED | FILED
    ) {
    }
}
