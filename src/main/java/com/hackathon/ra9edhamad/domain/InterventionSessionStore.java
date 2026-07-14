package com.hackathon.ra9edhamad.domain;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory store of live intervention sessions, keyed by sessionId. */
@Component
public class InterventionSessionStore {

    private final ConcurrentHashMap<String, InterventionSession> sessions = new ConcurrentHashMap<>();

    public InterventionSession open(GroundedContext context) {
        String id = "sess_" + UUID.randomUUID().toString().substring(0, 8);
        InterventionSession session = new InterventionSession(id, context);
        sessions.put(id, session);
        return session;
    }

    /** Returns the live session, or null if missing or expired (expired ones are evicted). */
    public InterventionSession get(String sessionId) {
        InterventionSession s = sessions.get(sessionId);
        if (s == null) {
            return null;
        }
        if (s.isExpired()) {
            sessions.remove(sessionId);
            return null;
        }
        return s;
    }

    public void close(String sessionId) {
        sessions.remove(sessionId);
    }
}
