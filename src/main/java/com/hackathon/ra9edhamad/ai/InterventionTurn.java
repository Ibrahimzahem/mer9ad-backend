package com.hackathon.ra9edhamad.ai;

/** One AI turn in the grey-zone dialogue. {@code message} is Arabic, shown to the customer. */
public record InterventionTurn(
        Action action,
        String message,
        boolean coachingDetected,
        String observedPattern        // nullable; a newly observed manipulation pattern
) {
    public enum Action {ASK, CLEAR, ESCALATE}

    public static InterventionTurn ask(String message) {
        return new InterventionTurn(Action.ASK, message, false, null);
    }

    public static InterventionTurn ask(String message, boolean coachingDetected, String pattern) {
        return new InterventionTurn(Action.ASK, message, coachingDetected, pattern);
    }

    public static InterventionTurn clear(String message, String pattern) {
        return new InterventionTurn(Action.CLEAR, message, false, pattern);
    }

    public static InterventionTurn escalate(String message, String pattern) {
        return new InterventionTurn(Action.ESCALATE, message, true, pattern);
    }
}
