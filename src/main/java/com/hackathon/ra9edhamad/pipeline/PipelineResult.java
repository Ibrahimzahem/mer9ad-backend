package com.hackathon.ra9edhamad.pipeline;

import com.hackathon.ra9edhamad.domain.Decision;

/**
 * Outcome of assessing a transfer intent. {@code sessionId}/{@code question} are present only
 * for ORANGE; {@code reportReference} only for RED.
 */
public record PipelineResult(
        String decisionId,
        Decision decision,
        String message,
        String sessionId,
        String question,
        String reportReference
) {
    public static PipelineResult green(String id, String message) {
        return new PipelineResult(id, Decision.GREEN, message, null, null, null);
    }

    public static PipelineResult orange(String id, String message, String sessionId, String question) {
        return new PipelineResult(id, Decision.ORANGE, message, sessionId, question, null);
    }

    public static PipelineResult red(String id, String message, String reportReference) {
        return new PipelineResult(id, Decision.RED, message, null, null, reportReference);
    }
}
