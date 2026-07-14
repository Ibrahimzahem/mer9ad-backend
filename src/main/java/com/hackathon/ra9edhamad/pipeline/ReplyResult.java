package com.hackathon.ra9edhamad.pipeline;

import com.hackathon.ra9edhamad.domain.Decision;

/**
 * Outcome of one intervention reply. {@code resolved=false} → keep asking; {@code resolved=true}
 * → {@code finalDecision} is GREEN or RED.
 */
public record ReplyResult(
        boolean resolved,
        String message,
        Decision finalDecision   // null while still asking
) {
    public static ReplyResult ask(String message) {
        return new ReplyResult(false, message, null);
    }

    public static ReplyResult resolved(String message, Decision finalDecision) {
        return new ReplyResult(true, message, finalDecision);
    }
}
