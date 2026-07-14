package com.hackathon.ra9edhamad.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A live grey-zone (ORANGE) intervention. Holds the grounded facts and the running dialogue so
 * the AI can ask off-script follow-ups and reflect contradictions back to the victim. In-memory
 * only, with a short TTL.
 */
public class InterventionSession {

    /** One line of the conversation. */
    public record Turn(String speaker, String text) {
    }   // speaker: "shield" | "customer"

    private static final Duration TTL = Duration.ofMinutes(3);
    // Max Shield questions before the transfer is held: opening + two follow-ups. The block
    // itself is the fourth (and final) message, giving the customer up to ~4 messages in all.
    public static final int MAX_TURNS = 3;

    private final String sessionId;
    private final String customerRef;
    private final GroundedContext context;
    private final List<Turn> history = new ArrayList<>();
    private final Instant createdAt = Instant.now();
    private final Instant expiresAt = createdAt.plus(TTL);
    private int turnCount; // number of questions Shield has asked

    public InterventionSession(String sessionId, GroundedContext context) {
        this.sessionId = sessionId;
        this.customerRef = context.customerRef();
        this.context = context;
    }

    public String sessionId() {
        return sessionId;
    }

    public String customerRef() {
        return customerRef;
    }

    public GroundedContext context() {
        return context;
    }

    public List<Turn> history() {
        return history;
    }

    public int turnCount() {
        return turnCount;
    }

    public void recordShieldQuestion(String text) {
        history.add(new Turn("shield", text));
        turnCount++;
    }

    public void recordCustomerAnswer(String text) {
        history.add(new Turn("customer", text));
    }

    public boolean turnsExhausted() {
        return turnCount >= MAX_TURNS;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
