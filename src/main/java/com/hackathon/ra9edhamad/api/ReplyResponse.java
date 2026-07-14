package com.hackathon.ra9edhamad.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hackathon.ra9edhamad.agent.MasterAgent;
import com.hackathon.ra9edhamad.domain.Decision;
import com.hackathon.ra9edhamad.pipeline.ReplyResult;

/** Next dialogue turn, or the resolution. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplyResponse(
        Action action,
        String message,
        Decision finalDecision   // present only when RESOLVED
) {
    public enum Action {ASK, RESOLVED}

    public static ReplyResponse from(ReplyResult r) {
        return r.resolved()
                ? new ReplyResponse(Action.RESOLVED, r.message(), r.finalDecision())
                : new ReplyResponse(Action.ASK, r.message(), null);
    }

    public static ReplyResponse from(MasterAgent.MasterReplyResult r) {
        return r.resolved()
                ? new ReplyResponse(Action.RESOLVED, r.message(), r.finalDecision())
                : new ReplyResponse(Action.ASK, r.message(), null);
    }
}
