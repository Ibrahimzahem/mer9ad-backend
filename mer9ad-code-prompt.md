# Build Spec — Shield (درع / رشد): anti-social-engineering fraud backend

Build a **Spring Boot (Java 21, Maven)** prototype backend for a fintech anti-fraud system called **Shield**. It protects bank customers from **social-engineering / authorized-push-payment (APP) fraud** at the moment a transfer is initiated ("second zero"), before any money moves. It does **not** protect the bank's channels from hacking — it protects the human from manipulation.

The core thesis: a coached victim passes every "is this really you?" check, because it *is* them. So Shield detects **coercion**, not identity, and its standout feature is a **grey-zone intervention**: when a transfer is suspicious-but-not-confirmed, it neither approves nor blocks — it runs a short AI conversation that breaks the scammer's urgency and brings the victim back to awareness.

Keep this a clean **prototype**: a handful of well-named classes, in-memory stores, no auth, no over-engineering. Prioritize a working, demoable end-to-end flow.

## Tech stack
- Java 21, Spring Boot 3.x, Maven, Spring Web.
- **In-memory H2** for accounts/customer data, seeded at startup (Spring Data JPA or a simple seeded repository — your call, keep it light).
- In-memory `ConcurrentHashMap` stores for: live intervention sessions, the fraud-intelligence log, and the scam-script playbook.
- AI via **OpenRouter** (OpenAI-compatible) using Spring's `RestClient`. No real security layer.

## The decision pipeline (core)
Implement a `ShieldDecisionPipeline` that, given a transfer-intent request, does:

1. **Aggregate signals** into two separate families (do NOT mix them):
    - **Client signals** (untrusted, from the payload): `cadenceDeviation`, `deviceInconsistency`, `baselineSource`; and coercion signals: `ibanEntryMethod` (typed/pasted/autofilled), `maxIdleGapMs`, `activeCallDetected`, `remoteAccessAppDetected`, `purposeEntryMethod`, `purposeFilledAfterIbanMs`.
    - **Server-verified facts** (from H2/seed, the client never sends these): beneficiary `accountAgeDays`, `transitVelocity` (0–1), `muleWatchlistHit`, `verifiedBalance`, and the customer's history summary.

2. **Cheap deterministic triage** → `CLEAR` or `ESCALATE`. Triage is NOT the detector; it only spares the AI the obvious-clean cases. `ESCALATE` if any coercion signal is present, OR beneficiary account age ≤ 30 days, OR transitVelocity ≥ 0.4, OR amount > 50% of verified balance, OR a behavioral score ≥ 0.5. Note: "new beneficiary" alone is NOT a trigger (every add is new). `CLEAR` → return **GREEN** immediately, AI never called.

3. **AI fraud judge** (the actual detector, only for ESCALATE): pass the grounded context to the LLM; it returns `{ fraudLikelihood: 0–1, assessment: <Arabic string>, recommendation: ALLOW | CHALLENGE | BLOCK }`.

4. **Resolve with a guardrail** (enforce strictly):
    - AI `ALLOW` & likelihood < 0.40 → **GREEN**.
    - AI `BLOCK` & likelihood ≥ 0.75 **AND a server-verified hard fact** → **RED**. Hard fact = `muleWatchlistHit` OR (`accountAgeDays` ≤ 2 AND `transitVelocity` ≥ 0.8).
    - Everything else → **ORANGE** (opens the intervention dialogue).
    - **Trust principle, must hold:** client signals can only raise suspicion to ORANGE; an irreversible RED requires a server-verified hard fact, never the client's word alone. Recompute everything server-side; never trust a client-supplied verdict.

5. **Log every decision** to the fraud-intelligence store (see feature 2).

## The AI layer (OpenRouter)
- Define an interface `ShieldAi` with two methods: `judge(GroundedContext)` → verdict, and `intervene(InterventionSession, latestAnswer)` → next dialogue turn.
- Real impl `OpenRouterShieldAi` calls `POST https://openrouter.ai/api/v1/chat/completions` with header `Authorization: Bearer ${OPENROUTER_API_KEY}`, body `{ model, messages, temperature }`. Read response from `choices[0].message.content`.
- Config via env: `OPENROUTER_API_KEY`, `OPENROUTER_MODEL` (default to a fast Arabic-capable model, e.g. `google/gemini-2.5-flash` — make it overridable).
- **Grounding (anti-hallucination):** every prompt must instruct the model to reason ONLY over the verified facts and signals provided; it must not invent reasons. Pass facts as a compact JSON block in the user message.
- **Structured output:** instruct the model to return strict JSON only (no prose, no markdown fences). Parse defensively: strip any ```` ```json ```` fences, then parse; on failure, fall back to a safe default (treat as CHALLENGE / ORANGE).
- **Demo-safety fallback:** provide a `StubShieldAi` that returns deterministic canned Arabic responses for the seeded scenarios. Auto-select the stub when `OPENROUTER_API_KEY` is absent, so the live demo works even with no network/API. Log which impl is active at startup.

## Multi-turn adaptive intervention (the wow — ORANGE)
When the pipeline returns ORANGE, open an in-memory `InterventionSession` (id, customerRef, grounded facts, conversation history, turn count, TTL ~3 min) and return the AI's **first Arabic question** to the client.

The dialogue loop (max ~3 turns): the scammer pre-scripts the victim with answers to obvious questions, so the AI must ask **off-script** questions, read the answers for **coaching signals** (scripted/verbatim/too-fast/contradicting verified facts), and then **reflect the contradiction back to the victim in plain Arabic** to break the trance — e.g. "You said this is your cousin, but you've never sent them money and this account opened last week. Has someone told you what to write here?"

Each turn, `intervene(...)` returns `{ action: ASK | CLEAR | ESCALATE, message: <Arabic>, coachingDetected: bool, observedPattern: <string or null> }`:
- `ASK` → return the next question, increment turn.
- `CLEAR` → the customer convincingly demonstrated this is legitimate → finalize **GREEN**.
- `ESCALATE` → strong coaching detected / turns exhausted without clearing → finalize as a **step-up failure → RED** (block + report).
  Always end the session by writing a labeled record to the fraud-intel log.

## Visionary feature 1 — the scam-script playbook (learns the manipulation, not just the user)
Maintain a `ScamPlaybookStore` (seeded with a few known Arabic social-engineering patterns, e.g. "coached to write 'family expenses' for an unrelated new account," "transfer while on a live call with fake bank support," "pasted IBAN received via WhatsApp").
- Inject the current known patterns into the judge and intervene prompts as grounding ("known manipulation patterns: …").
- When an intervention detects coaching (`observedPattern` non-null), **append** the newly observed pattern to the store. This is the feedback mechanism — the system gets better at recognizing the attacker's playbook over time (no ML training needed for the prototype; it's a growing grounded pattern set).
- Expose `GET /api/scam-playbook` to show it growing — demo gold.

## Visionary feature 2 — grey-zone fraud-intelligence feedback loop
Every ORANGE/RED resolution writes a labeled record to a `FraudIntelStore`: `{ eventId, customerRef (pseudonymous), beneficiaryIban, decision, outcome (PROCEEDED_SAFE | ABANDONED | BLOCKED | CHALLENGE_FAILED), signalsSnapshot, detectedPattern, timestamp }`.
- Frame this as **labeled data on social engineering caught mid-act** — something banks normally never capture (they only learn from completed fraud) — conceptually feeding national/GCC mule intelligence (SAMA, Vision 2030).
- Expose `GET /api/fraud-intel` to view/export the labeled feed.

## API endpoints
- `POST /api/transfer-intent` — body = the transfer-intent payload (below). Returns `{ decisionId, decision, action (PROCEED|STEP_UP_CHALLENGE|BLOCK_AND_REPORT), message?, session? {sessionId, question}, reportReference? }`. Never return scores/thresholds/which-signal-fired — only what the UI renders.
- `POST /api/intervention/{sessionId}/reply` — body `{ answer }`. Returns `{ action: ASK | RESOLVED, message, finalDecision? (GREEN|RED) }`.
- `GET /api/fraud-intel` — the labeled feedback log.
- `GET /api/scam-playbook` — known + learned manipulation patterns.
- `GET /api/accounts` — seeded accounts (demo visibility).

### Transfer-intent payload (shape)
```json
{
  "eventId": "evt_...",
  "customerRef": "cust_8842",
  "transfer": { "beneficiaryIban": "SA...", "amount": 900.0, "isNewBeneficiary": true,
    "purpose": { "text": "مصاريف عائلية", "entryMethod": "pasted" } },
  "signals": {
    "behavioral": { "cadenceDeviation": 0.31, "cadenceSamples": 5, "deviceInconsistency": 0.12, "baselineSource": "population" },
    "coercion": { "ibanEntryMethod": "pasted", "maxIdleGapMs": 25300, "activeCallDetected": true,
      "remoteAccessAppDetected": false, "purposeEntryMethod": "pasted", "purposeFilledAfterIbanMs": 800 }
  }
}
```

## Seed scenarios (must demo out of the box)
Seed one customer (`cust_8842`) with a salary payee + a couple of established payees in history, balance 1000 SAR, and three beneficiary accounts:
1. **Established** (`SA..1111`, age 800d, transit 0.1, not mule, in customer history) → triage CLEAR → **GREEN**.
2. **New + high transit** (`SA..2222`, age 3d, transit 0.85, not mule) → ESCALATE → AI → **ORANGE** multi-turn dialogue (the showcase: age 3 fails the ≤2-day hard-fact test, so no auto-block — it must talk to the victim).
3. **Mule** (`SA..3333`, age 10d, transit 0.9, muleWatchlistHit=true) → ESCALATE → AI BLOCK + hard fact → **RED**.

## Keep it simple (prototype scope)
- No real auth/JWT; trust `customerRef` from the body.
- "Report to fraud dept" = log line + a record in the fraud-intel store (mock a `reportReference`).
- In-memory sessions/stores; no external DB beyond H2.
- A few clear packages: `api` (controllers + DTOs), `pipeline` (triage + resolve), `ai` (ShieldAi + OpenRouter + stub), `domain` (accounts, history, stores), `config`.
- Reasonable defaults, a `README` with run steps and example `curl`s for all three scenarios.

## Done when
`./mvnw spring-boot:run` starts with seeded data; the three `curl` scenarios produce GREEN, an ORANGE multi-turn conversation that can resolve to GREEN or RED based on the answers, and RED; and `/api/fraud-intel` + `/api/scam-playbook` visibly populate as you run interventions. Works with the stub when no API key is set, and with OpenRouter when `OPENROUTER_API_KEY` is provided.