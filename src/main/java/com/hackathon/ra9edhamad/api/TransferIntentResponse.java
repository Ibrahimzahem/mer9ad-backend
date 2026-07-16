package com.hackathon.ra9edhamad.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hackathon.ra9edhamad.agent.Agent;
import com.hackathon.ra9edhamad.agent.AgentStepInfo;
import com.hackathon.ra9edhamad.agent.MasterAgent;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.pipeline.PipelineResult;

import java.util.List;

/**
 * What the UI renders. Deliberately never exposes scores, thresholds, or which signal fired.
 * The {@code agents} field is for the mission-control panel — it shows what each specialist
 * agent decided, making the multi-agent architecture visible in the demo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferIntentResponse(
        String decisionId,
        Decision decision,
        Action action,
        String message,
        SessionInfo session,
        String reportReference,
        List<AgentVerdictInfo> agents,
        SarInfo sar,
        LessonInfo lesson
) {
    public enum Action {PROCEED, STEP_UP_CHALLENGE, BLOCK_AND_REPORT}

    public record SessionInfo(String sessionId, String question) {
    }

    public record AgentVerdictInfo(
            String agent,
            Decision decision,
            double score,
            String evidence,
            String ruleId,
            List<AgentStepInfo> trace
    ) {
        public static AgentVerdictInfo from(Agent.AgentVerdict v, String agentName) {
            List<AgentStepInfo> traceInfo = null;
            if (v.trace() != null && v.trace().steps() != null) {
                traceInfo = v.trace().steps().stream()
                        .map(AgentStepInfo::from)
                        .toList();
            }
            return new AgentVerdictInfo(agentName, v.decision(), v.score(), v.evidence(), v.ruleId(), traceInfo);
        }
    }

    public record SarInfo(
            String sarId,
            String timestamp,
            String narrative,
            String status,
            int factorCount
    ) {
        public static SarInfo from(com.hackathon.ra9edhamad.agent.ComplianceAgent.SarDraft s) {
            if (s == null) return null;
            return new SarInfo(s.sarId(), s.timestamp(), s.narrative(), s.status(),
                    s.factors() != null ? s.factors().size() : 0);
        }
    }

    public record LessonInfo(
            String scamName,
            String lesson,
            java.util.List<String> signs,
            String action,
            String outcome,
            int playbookSize
    ) {
        public static LessonInfo from(com.hackathon.ra9edhamad.agent.LiteracyAgent.MicroLesson l) {
            if (l == null) return null;
            return new LessonInfo(l.scamName(), l.lesson(), l.signs(), l.action(), l.outcome(), l.playbookSize());
        }
    }

    public static TransferIntentResponse from(PipelineResult r) {
        return switch (r.decision()) {
            case GREEN -> new TransferIntentResponse(r.decisionId(), Decision.GREEN, Action.PROCEED,
                    r.message(), null, null, null, null, null);
            case ORANGE -> new TransferIntentResponse(r.decisionId(), Decision.ORANGE, Action.STEP_UP_CHALLENGE,
                    r.message(), new SessionInfo(r.sessionId(), r.question()), null, null, null, null);
            case RED -> new TransferIntentResponse(r.decisionId(), Decision.RED, Action.BLOCK_AND_REPORT,
                    r.message(), null, r.reportReference(), null, null, null);
        };
    }

    public static TransferIntentResponse from(MasterAgent.MasterResult r) {
        List<AgentVerdictInfo> agentInfos = null;
        if (r.agentVerdicts() != null) {
            agentInfos = r.agentVerdicts().stream()
                    .map(v -> AgentVerdictInfo.from(v, inferAgentName(v, r)))
                    .toList();
        }

        SarInfo sarInfo = SarInfo.from(r.sarDraft());
        LessonInfo lessonInfo = LessonInfo.from(r.lesson());

        return switch (r.decision()) {
            case GREEN -> new TransferIntentResponse(r.decisionId(), Decision.GREEN, Action.PROCEED,
                    r.message(), null, null, agentInfos, null, null);
            case ORANGE -> new TransferIntentResponse(r.decisionId(), Decision.ORANGE, Action.STEP_UP_CHALLENGE,
                    r.message(),
                    r.sessionId() != null ? new SessionInfo(r.sessionId(), r.question()) : null,
                    null, agentInfos, null, null);
            case RED -> new TransferIntentResponse(r.decisionId(), Decision.RED, Action.BLOCK_AND_REPORT,
                    r.message(), null, r.reportReference(), agentInfos, sarInfo, lessonInfo);
        };
    }

    private static String inferAgentName(Agent.AgentVerdict v, MasterAgent.MasterResult r) {
        return v.ruleId() != null && v.ruleId().startsWith("AML") ? "AmlAgent"
                : v.ruleId() != null && v.ruleId().startsWith("FRAUD") ? "FraudAgent"
                : "Agent";
    }
}
