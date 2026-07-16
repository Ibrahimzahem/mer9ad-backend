# Frontend spec — Shield demo (درع / رشد)

You are a frontend Claude Code agent. Build a demo frontend for the **Shield** anti-social-engineering fraud backend. You have only this file and a running backend — everything you need is here. No guessing: every endpoint, field, and seeded value below is taken verbatim from the backend implementation.

Shield protects a bank customer from **social-engineering / authorized-push-payment (APP) fraud** at the moment they initiate a transfer. It detects **coercion, not identity**. Under the hood it's a small **multi-agent system** (a FraudAgent, an AmlAgent, a ComplianceAgent, a LiteracyAgent, orchestrated by a MasterAgent) — but the customer-facing product is still just one flow: assess → maybe intervene → resolve. Its centerpiece is the **grey-zone (ORANGE) intervention**: when a transfer is suspicious-but-unconfirmed, the backend neither approves nor blocks — it opens a short AI conversation that breaks the scammer's manufactured urgency and brings the victim back to awareness.

---

## Actual API contract

**Base URL:** `http://localhost:8080` (override via `VITE_API_BASE`). No auth.

**CORS:** `WebCorsConfig.java` maps `/api/**` for origins `http://localhost:5173`, `http://localhost:8080`, and the production domain, methods `GET, POST, OPTIONS`, all headers. Run the frontend dev server on **port 5173** (or 8080) — any other origin is blocked by the browser. Note: `/open-banking/v1/**` is **not** covered by this CORS mapping — it's a separate partner-bank/regulator API, not meant to be called directly from this browser app (see the endpoints table).

All request/response bodies are JSON (`Content-Type: application/json`). Response DTOs use `@JsonInclude(NON_NULL)` — **null fields are omitted entirely**, so fields like `session`, `reportReference`, `sar`, `lesson` only appear when relevant.

**Live AI vs. offline demo mode:** the backend can run two ways, controlled by env vars on the *backend* (nothing the frontend controls):
- **No keys set (default hackathon mode):** fully deterministic — `StubShieldAi` for the fraud judge/intervention chat, rule-based `AmlAgent`. Exact Arabic wording is fixed (documented below) and reproducible every run.
- **`SHIELD_AI=groq` + `GROQ_API_KEY` (+ optionally `OPENROUTER_API_KEY`):** the FraudAgent/AmlAgent run a real ReAct tool-calling investigation, then hand their findings to a live model for the final judgment. Wording will vary run to run, and verdicts may include a non-null `trace` (see below). **The frontend must never hardcode exact AI message text** — only rely on the JSON *shape* (`action`, `decision`, etc.); render whatever `message`/`assessment` string comes back.

### Endpoints

| Method & path | Purpose |
|---|---|
| `GET /api/customer` | The static demo customer to load on startup: profile, balance, payee list, transaction history. **This is your source of truth — fetch it, don't hardcode names.** |
| `POST /api/transfer-intent` | Assess a transfer. Returns a GREEN / ORANGE / RED decision, the per-agent verdicts, and (on RED) a SAR draft + literacy micro-lesson. |
| `POST /api/intervention/{sessionId}/reply` | Continue the grey-zone dialogue (only after an ORANGE). |
| `GET /api/demo/scenarios` | List of 4 canned demo scenarios (id, name, description, expected outcome) — for a demo-controls panel. |
| `GET /api/demo/scenarios/{id}` | The exact ready-to-POST `transfer-intent` request body for scenario `scam \| mule \| structuring \| clean`. Use this instead of hand-building payloads for your demo-controls buttons. |
| `GET /api/fraud-intel` | Labeled grey-zone outcome feed (grows after each ORANGE/RED), including the reasoning trace when one was captured. |
| `GET /api/scam-playbook` | Known + newly-learned manipulation patterns (array of Arabic strings). |
| `GET /api/accounts` | The seeded beneficiary accounts incl. holder name + risk facts (demo visibility). |
| *(not for this app)* `POST/GET /open-banking/v1/*` | A separate partner-bank/regulator API (contribute intel, query IBAN reputation, network overview). Not CORS-enabled for this origin — ignore it unless you're specifically asked to build the regulator view. |

---

### `GET /api/customer`

The single static demo customer the app renders on load. `GET /api/customer` returns `cust_8842`
directly; `GET /api/customer/{customerRef}` also works (404 if unknown). **Fetch this on startup and
drive the whole UI from it** — the names, balance, payee list and statement are all real, no invention
needed. Exact shape (10 beneficiaries, 12 transactions):

```json
{
  "customerRef": "cust_8842",
  "name": "ياسر العوفي",
  "iban": "SA0000000000000000008842",
  "balance": 8500.0,
  "beneficiaries": [
    { "name": "خالد العتيبي", "iban": "SA0000000000000000001111", "relationship": "صديق", "saved": true },
    { "name": "عبدالله المطيري", "iban": "SA0000000000000000004444", "relationship": "إيجار السكن", "saved": true },
    { "name": "متجر سلة", "iban": "SA0000000000000000005555", "relationship": "متجر إلكتروني", "saved": true },
    { "name": "شركة الرياض للتقنية", "iban": "SA0000000000000000000000", "relationship": "جهة العمل", "saved": true },
    { "name": "سعد الدوسري", "iban": "SA0000000000000000006666", "relationship": "صديق (جديد)", "saved": false },
    { "name": "عبدالعزيز الشمري", "iban": "SA0000000000000000002222", "relationship": "مستفيد جديد", "saved": false },
    { "name": "سلطان الغامدي", "iban": "SA0000000000000000003333", "relationship": "مستفيد جديد", "saved": false },
    { "name": "ماجد القحطاني", "iban": "SA0000000000000000007777", "relationship": "مستفيد جديد", "saved": false },
    { "name": "Ahmed Trading LLC", "iban": "SA0000000000000000008888", "relationship": "تحويل دولي", "saved": false },
    { "name": "مؤسسة الفجر التجارية", "iban": "SA0000000000000000009999", "relationship": "مؤسسة تجارية", "saved": false }
  ],
  "transactions": [
    { "date": "2026-06-01", "counterparty": "شركة الرياض للتقنية", "iban": "SA0000000000000000000000", "amount": 9000.0, "direction": "IN",  "category": "راتب" },
    { "date": "2026-06-02", "counterparty": "عبدالله المطيري",   "iban": "SA0000000000000000004444", "amount": 2500.0, "direction": "OUT", "category": "إيجار" },
    { "date": "2026-06-05", "counterparty": "متجر سلة",          "iban": "SA0000000000000000005555", "amount": 260.0,  "direction": "OUT", "category": "تسوق" },
    { "date": "2026-06-11", "counterparty": "خالد العتيبي",      "iban": "SA0000000000000000001111", "amount": 500.0,  "direction": "OUT", "category": "تحويل شخصي" },
    { "date": "2026-06-17", "counterparty": "متجر سلة",          "iban": "SA0000000000000000005555", "amount": 180.0,  "direction": "OUT", "category": "تسوق" },
    { "date": "2026-06-22", "counterparty": "متجر سلة",          "iban": "SA0000000000000000005555", "amount": 95.0,   "direction": "OUT", "category": "تسوق" },
    { "date": "2026-06-28", "counterparty": "خالد العتيبي",      "iban": "SA0000000000000000001111", "amount": 300.0,  "direction": "OUT", "category": "تحويل شخصي" },
    { "date": "2026-06-30", "counterparty": "متجر سلة",          "iban": "SA0000000000000000005555", "amount": 210.0,  "direction": "OUT", "category": "تسوق" },
    { "date": "2026-07-10", "counterparty": "مؤسسة الفجر التجارية", "iban": "SA0000000000000000009999", "amount": 8500.0, "direction": "OUT", "category": "دفعات" },
    { "date": "2026-07-10", "counterparty": "مؤسسة الفجر التجارية", "iban": "SA0000000000000000009999", "amount": 7200.0, "direction": "OUT", "category": "دفعات" },
    { "date": "2026-07-10", "counterparty": "مؤسسة الفجر التجارية", "iban": "SA0000000000000000009999", "amount": 6800.0, "direction": "OUT", "category": "دفعات" },
    { "date": "2026-07-10", "counterparty": "مؤسسة الفجر التجارية", "iban": "SA0000000000000000009999", "amount": 5500.0, "direction": "OUT", "category": "دفعات" }
  ],
  "historySummary": "العميل لديه راتب شهري ومستفيدون معتمدون سابقون؛ لا يوجد سجل تحويلات لحسابات حديثة."
}
```

Field notes:
- `beneficiaries[].saved` = `true` for the customer's **trusted/established** payees, `false` for **new/ad-hoc** ones. Use it to split the UI into a *saved payees* list and a *new payee* flow. It is **not** a risk signal — the risk facts stay server-side and only surface as a decision after `/api/transfer-intent`.
- `beneficiaries[].relationship` is the customer's own free-text label (Arabic), for display.
- `transactions[].direction` ∈ `"IN" | "OUT"`; `amount` is always positive (use `direction` for the sign). Render newest-last as given, or reverse for a newest-first statement — your call.
- The four `2026-07-10` transactions to `مؤسسة الفجر التجارية` are the **structuring/smurfing** seed: four same-day sub-threshold transfers to one payee. They already exist in this customer's own history — you don't need to create them; they're what the AmlAgent's structuring detector reacts to when the customer transfers to that IBAN again (the "structuring" demo scenario, see below).
- Nothing here reveals which payee is a mule; that is intentional. Don't infer risk from this payload.

---

### `POST /api/transfer-intent`

**Request body** — exact shape (field names and types are literal; `amount` is a number, `*Ms` are integers, booleans are booleans):

```json
{
  "eventId": "evt_1",
  "customerRef": "cust_8842",
  "transfer": {
    "beneficiaryIban": "SA0000000000000000001111",
    "amount": 100.0,
    "isNewBeneficiary": false,
    "purpose": { "text": "سداد", "entryMethod": "typed" }
  },
  "signals": {
    "behavioral": {
      "cadenceDeviation": 0.1,
      "cadenceSamples": 6,
      "deviceInconsistency": 0.1,
      "baselineSource": "personal"
    },
    "coercion": {
      "ibanEntryMethod": "typed",
      "maxIdleGapMs": 1000,
      "activeCallDetected": false,
      "remoteAccessAppDetected": false,
      "purposeEntryMethod": "typed",
      "purposeFilledAfterIbanMs": 6000
    }
  }
}
```

Field notes:
- `entryMethod` / `ibanEntryMethod` / `purposeEntryMethod` ∈ `"typed" | "pasted" | "autofilled"`. Anything other than `"typed"` counts as a coercion signal.
- `baselineSource` is a free string (e.g. `"personal"` | `"population"`); informational only.
- `maxIdleGapMs`, `purposeFilledAfterIbanMs` are integer milliseconds.
- The backend **recomputes every server-side fact itself** (account age, transit velocity, mule flag, balance). The client never sends these and cannot influence them.
- **IBAN validation:** `beneficiaryIban` must be a valid Saudi IBAN = `SA` + exactly 22 digits (24 chars total, uppercase, no spaces). A malformed IBAN is rejected **before** the pipeline with **HTTP 400** (see below).

**Response** — `decision` ∈ `GREEN | ORANGE | RED`; `action` ∈ `PROCEED | STEP_UP_CHALLENGE | BLOCK_AND_REPORT`. `decisionId` is always present (`dec_` + 8 hex chars). Every response also carries an `agents` array — one verdict per specialist agent that ran — and RED responses add `sar` and `lesson`. Full field list:

```
decisionId        string            always
decision          GREEN|ORANGE|RED  always
action            PROCEED|STEP_UP_CHALLENGE|BLOCK_AND_REPORT   always
message           string            always
session           { sessionId, question }   ORANGE only
reportReference   string ("RPT-" + 8 hex)   RED only
agents            AgentVerdictInfo[]        always (see below)
sar               SarInfo                   RED only
lesson            LessonInfo                RED only
```

**`agents[]`** — one entry per specialist agent (currently 4 run every time: the fraud agent, the AML agent, and two agents that only activate on RED — `ComplianceAgent`/`LiteracyAgent` — which always report a neutral GREEN "ready" verdict the rest of the time):

```json
{
  "agent": "FraudAgent",
  "decision": "ORANGE",
  "score": 0.6,
  "evidence": "الحساب المستفيد حديث جدًا وتتسارع الأموال خارجه بشكل غير طبيعي...",
  "ruleId": "FRAUD_REASONING_AGENT_CHALLENGE",
  "trace": [
    { "step": 1, "type": "THOUGHT", "toolName": null, "toolArguments": null, "content": "...", "durationMs": 210 },
    { "step": 2, "type": "TOOL_CALL", "toolName": "checkMuleWatchlist", "toolArguments": "{\"iban\":\"...\"}", "content": null, "durationMs": 5 },
    { "step": 3, "type": "TOOL_RESULT", "toolName": "checkMuleWatchlist", "toolArguments": null, "content": "NOT_FOUND", "durationMs": 5 },
    { "step": 4, "type": "FINAL_ANSWER", "toolName": null, "toolArguments": null, "content": "{...}", "durationMs": 3 }
  ]
}
```
- `agent` is inferred from the rule-id prefix: rule IDs starting with `FRAUD` → `"FraudAgent"`, `AML` → `"AmlAgent"`, anything else (the two always-GREEN placeholder agents, ruleId `COMPLIANCE_READY` / `LITERACY_READY`) → generic `"Agent"`. **Recommendation:** in your mission-control panel, either hide entries named `"Agent"` with a `_READY` ruleId, or show them collapsed as "compliance/literacy standing by" — don't present them as if they made a real fraud judgment on this transfer.
- `trace` is `null`/absent for deterministic verdicts (rule triage, mule hard-fact block, or when the backend is running with no AI key). It's only populated when the reasoning ReAct loop actually ran. **This is the flashiest part of the demo** — if present, render it as an expandable "agent reasoning" trace: THOUGHT/TOOL_CALL/TOOL_RESULT/FINAL_ANSWER steps in order, each with `toolName`/`toolArguments`/`content`/`durationMs`.
- `score` is 0..1 risk score from that agent's own lens; `ruleId` is the stable rule/model id that fired (for audit — fine to show in a developer/debug view, not needed for the customer-facing screens).

**`sar`** (RED only) — a compliance report draft:
```json
{
  "sarId": "SAR-RPT-AB12CD34",
  "timestamp": "2026-07-16T12:00:00.123456Z",
  "narrative": "بلاغ نشاط مشبوه (SAR)\n\nالتاريخ: ...\nالعميل: cust_8842\n...",
  "status": "DRAFT",
  "factorCount": 2
}
```

**`lesson`** (RED only) — a just-in-time financial-literacy micro-lesson for the customer:
```json
{
  "scamName": "حسابات تمرير الأموال (Mule)",
  "lesson": "هذا الاحتيال الذي تم إيقاف التحويل وحماية أموالك يُسمى «حسابات تمرير الأموال (Mule)». لحمايتك في المستقبل...",
  "signs": [
    "الحساب المستفيد حديث الإنشاء وغير معروف لك",
    "المتصل يدّعي أنه من البنك ويطلب تحويلًا عاجلاً",
    "ضغط وإلحاح على إتمام التحويل فورًا"
  ],
  "action": "أنهِ المكالمة فورًا واتصل بالبنك على الرقم المكتوب خلف بطاقتك. لا تحوّل أي مبلغ بناءً على مكالمة هاتفية.",
  "outcome": "blocked",
  "playbookSize": 3
}
```
`outcome` ∈ `"blocked" | "intercepted"`. `signs` is always exactly 3 short Arabic warning signs. This is meant to be shown on the RED block screen as a small "what just happened, and what to watch for next time" card.

**GREEN** (only `decisionId`, `decision`, `action`, `message`, `agents`):
```json
{
  "decisionId": "dec_1a2b3c4d",
  "decision": "GREEN",
  "action": "PROCEED",
  "message": "تمت الموافقة على التحويل.",
  "agents": [
    { "agent": "FraudAgent", "decision": "GREEN", "score": 0.0, "evidence": "لا توجد إشارات احتيال أو إكراه.", "ruleId": "FRAUD_TRIAGE_CLEAR" },
    { "agent": "AmlAgent", "decision": "GREEN", "score": 0.0, "evidence": "لا توجد أنماط غسيل أموال.", "ruleId": "AML_CLEAR" },
    { "agent": "Agent", "decision": "GREEN", "score": 0.0, "evidence": "وكالة الامتثال جاهزة لإصدار البلاغات.", "ruleId": "COMPLIANCE_READY" },
    { "agent": "Agent", "decision": "GREEN", "score": 0.0, "evidence": "وكالة التثقيف جاهزة.", "ruleId": "LITERACY_READY" }
  ]
}
```

**ORANGE** (adds `session`; `session.sessionId` = `sess_` + 8 hex chars; `session.question` is the AI's first Arabic question — it equals `message`):
```json
{
  "decisionId": "dec_1a2b3c4d",
  "decision": "ORANGE",
  "action": "STEP_UP_CHALLENGE",
  "message": "قبل أن نُكمل، من فضلك أخبرني بكلماتك أنت: من صاحب هذا الحساب بالضبط، ومتى تعرّفت عليه، ولماذا تُحوّل له الآن؟",
  "session": {
    "sessionId": "sess_9f8e7d6c",
    "question": "قبل أن نُكمل، من فضلك أخبرني بكلماتك أنت: من صاحب هذا الحساب بالضبط، ومتى تعرّفت عليه، ولماذا تُحوّل له الآن؟"
  },
  "agents": [ /* same shape as above */ ]
}
```

**RED** (adds `reportReference`, `sar`, `lesson`; no `session`):
```json
{
  "decisionId": "dec_1a2b3c4d",
  "decision": "RED",
  "action": "BLOCK_AND_REPORT",
  "message": "تم إيقاف التحويل: الحساب المستفيد مُدرج ضمن قائمة حسابات تمرير الأموال. هذا قرار قطعي لحمايتك.",
  "reportReference": "RPT-AB12CD34",
  "agents": [ /* ... */ ],
  "sar": { /* ... */ },
  "lesson": { /* ... */ }
}
```

**HTTP 400 — invalid IBAN** (thrown before assessment; `beneficiaryIban` echoes back what was sent):
```json
{
  "error": "INVALID_IBAN",
  "message": "Invalid Saudi IBAN: it must be 'SA' followed by exactly 22 digits (24 characters total).",
  "beneficiaryIban": "SA00000000000003333"
}
```

The customer-facing screens should stick to `decision`/`action`/`message`/`session`/`reportReference` — never show raw `score`/`ruleId` to the customer. `agents`/`sar`/`lesson` are for the judge-facing mission-control view (section 6) and the RED "what happened" card.

---

### `POST /api/intervention/{sessionId}/reply`

Use the `session.sessionId` from an ORANGE response as the path segment.

**Request body:**
```json
{ "answer": "هذا حساب صديقي خالد الذي أعرفه منذ سنوات وأسدد له ثمن سيارة اشتريتها منه" }
```

**Response** — `action` ∈ `ASK | RESOLVED`. `finalDecision` (∈ `GREEN | RED`) is present **only** when `action` is `RESOLVED`.

Keep asking (`ASK`, no `finalDecision`):
```json
{
  "action": "ASK",
  "message": "ذكرت أن الغرض «مصاريف عائلية»، لكن هذا الحساب فُتح قبل أيام قليلة ولم تُرسل إليه من قبل. هل طلب منك شخصٌ الآن أن تكتب هذا الغرض تحديدًا؟ اكتب لي اسم صاحب الحساب وصلة قرابتك به."
}
```

Resolved to GREEN (victim's answer was convincing / self-sourced):
```json
{
  "action": "RESOLVED",
  "message": "شكرًا لتوضيحك. تبدو التفاصيل متّسقة ونابعة منك. يمكنك المتابعة بأمان.",
  "finalDecision": "GREEN"
}
```

Resolved to RED (coaching detected / scam language / turns exhausted):
```json
{
  "action": "RESOLVED",
  "message": "إجاباتك تتطابق مع نص مُحضَّر مسبقًا ولا تتوافق مع حقائق الحساب. لحمايتك، سنوقف هذا التحويل الآن. إن كان أحدٌ يوجّهك عبر الهاتف، فمن المرجّح أنه محتال.",
  "finalDecision": "RED"
}
```

Session behavior (from the backend): sessions live **3 minutes** and the conversation runs up to **~4 Shield messages** before the transfer is held. If you POST a reply to an unknown/expired `sessionId`, you get a resolved RED: `{"action":"RESOLVED","message":"انتهت صلاحية الجلسة.","finalDecision":"RED"}`.

**Deterministic offline behavior** (the demo runs with no API key, using the built-in `StubShieldAi` — applies whenever the backend has no `SHIELD_AI`/key configured):
1. **Instant ESCALATE on scam language, regardless of turn count:** if the customer's own reply describes a classic scam pattern in their own words (a promise to double the money, a prince/inheritance/lottery, crypto/forex with guaranteed profit, someone impersonating the bank/police/a government body, or a relationship with someone "met online"/never in person), it's held immediately — no need to exhaust the 3-question budget. Judge the *content*, not the tone.
2. Otherwise, a reply is treated as *coached* only when it is blank, shorter than 5 characters, or contains the verbatim planted purpose `"مصاريف عائلية"`. Three coached answers in a row → RED (with two `ASK` messages in between); any single detailed, self-sourced answer → GREEN.
3. The Shield voice is professional bank customer-service in tone throughout.

If the backend is instead running with a live model (`SHIELD_AI=groq`/`OPENROUTER_API_KEY`), the JSON contract (`action`/`message`/`finalDecision`) is identical, but the exact wording and how many turns it takes will vary — don't hardcode the Arabic strings above as literal match targets, only as illustrative examples.

---

### `GET /api/fraud-intel`

Returns a JSON **array** of records, oldest first. Grows by one every time a transfer resolves to ORANGE (`outcome: "CHALLENGE_ISSUED"`), RED via mule (`"BLOCKED"`), RED via failed challenge (`"CHALLENGE_FAILED"`), or GREEN via a passed challenge (`"PROCEEDED_SAFE"`). Record shape:

```json
[
  {
    "eventId": "evt_3",
    "customerRef": "cust_8842",
    "beneficiaryIban": "SA0000000000000000003333",
    "decision": "RED",
    "outcome": "BLOCKED",
    "signalsSnapshot": {
      "amount": 900.0,
      "beneficiaryIban": "SA0000000000000000003333",
      "driverRule": "FRAUD_MULE_HARD_FACT",
      "driverScore": 1.0,
      "agents": ["FraudAgent", "AmlAgent", "ComplianceAgent", "LiteracyAgent"]
    },
    "detectedPattern": null,
    "timestamp": "2026-06-30T12:00:00.000000Z",
    "trace": null
  }
]
```

- `decision` ∈ `GREEN | ORANGE | RED`; `outcome` ∈ `PROCEEDED_SAFE | ABANDONED | BLOCKED | CHALLENGE_FAILED | CHALLENGE_ISSUED`.
- `signalsSnapshot` is a flat object; treat it as opaque debug context and display it read-only (don't rely on exact keys beyond `amount`/`beneficiaryIban`).
- `detectedPattern` is a nullable Arabic string (set when the intervention learns a fresh coaching pattern).
- `timestamp` is ISO-8601 UTC.
- `trace` is the same `AgentStepInfo[]` shape documented under `agents[].trace` above — **nullable**. It's only populated when the driving agent's decision came from a real reasoning run (live AI configured on the backend); with no API key configured it will always be `null`. When present, it's the same "reasoning chain" data you'd render in the mission-control panel — this endpoint is how you'd let a judge come back later and inspect *why* a past decision was made, not just what it was.

### `GET /api/scam-playbook`

Returns a JSON **array of Arabic strings**. Seeded with three known patterns; a new one is appended whenever an intervention detects a fresh coaching script. Seeded content:

```json
[
  "تلقين الضحية كتابة \"مصاريف عائلية\" كغرض لتحويل إلى حساب جديد لا علاقة له بالعائلة",
  "إجراء التحويل أثناء مكالمة مباشرة مع محتال ينتحل صفة دعم البنك",
  "لصق رقم آيبان (IBAN) تم استلامه عبر واتساب من جهة غير معروفة"
]
```

### `GET /api/accounts`

Returns a JSON **array** of the seeded beneficiary accounts (server-verified facts, incl. `holderName`). This is a **debug/demo-visibility** view — it *does* expose the risk facts, so don't wire it into the customer UI; use `/api/customer` for the payee list. Shape:

```json
[
  { "iban": "SA0000000000000000000000", "holderName": "شركة الرياض للتقنية", "accountAgeDays": 1200, "transitVelocity": 0.05, "muleWatchlistHit": false, "verifiedBalance": 999999.0 },
  { "iban": "SA0000000000000000001111", "holderName": "خالد العتيبي",       "accountAgeDays": 800,  "transitVelocity": 0.10, "muleWatchlistHit": false, "verifiedBalance": 50000.0 },
  { "iban": "SA0000000000000000004444", "holderName": "عبدالله المطيري",    "accountAgeDays": 600,  "transitVelocity": 0.08, "muleWatchlistHit": false, "verifiedBalance": 20000.0 },
  { "iban": "SA0000000000000000005555", "holderName": "متجر سلة",           "accountAgeDays": 300,  "transitVelocity": 0.20, "muleWatchlistHit": false, "verifiedBalance": 80000.0 },
  { "iban": "SA0000000000000000006666", "holderName": "سعد الدوسري",        "accountAgeDays": 20,   "transitVelocity": 0.50, "muleWatchlistHit": false, "verifiedBalance": 3000.0 },
  { "iban": "SA0000000000000000008888", "holderName": "Ahmed Trading LLC",  "accountAgeDays": 15,   "transitVelocity": 0.45, "muleWatchlistHit": false, "verifiedBalance": 2000.0 },
  { "iban": "SA0000000000000000002222", "holderName": "عبدالعزيز الشمري",   "accountAgeDays": 3,    "transitVelocity": 0.85, "muleWatchlistHit": false, "verifiedBalance": 1200.0 },
  { "iban": "SA0000000000000000003333", "holderName": "سلطان الغامدي",      "accountAgeDays": 10,   "transitVelocity": 0.90, "muleWatchlistHit": true,  "verifiedBalance": 4000.0 },
  { "iban": "SA0000000000000000007777", "holderName": "ماجد القحطاني",      "accountAgeDays": 5,    "transitVelocity": 0.95, "muleWatchlistHit": true,  "verifiedBalance": 6000.0 },
  { "iban": "SA0000000000000000009999", "holderName": "مؤسسة الفجر التجارية","accountAgeDays": 8,    "transitVelocity": 0.88, "muleWatchlistHit": false, "verifiedBalance": 12000.0 }
]
```

> Note: `/api/accounts` returns from a `ConcurrentHashMap`, so **array order is not guaranteed**. Never rely on index; match by `iban`.

### `GET /api/demo/scenarios` and `GET /api/demo/scenarios/{id}`

For your demo-controls panel (section 8), don't hand-build the four scenario payloads yourself — fetch them:

```
GET /api/demo/scenarios
```
```json
[
  { "id": "scam", "name": "احتيال هاتفي (Social Engineering)", "description": "مكالمة نشطة + مستفيد جديد + حساب سريع العبور → تدخل الوكيل", "expected": "ORANGE → intervention chat" },
  { "id": "mule", "name": "حساب تمرير أموال (Mule)", "description": "حساب مؤكد في قائمة المول → إيقاف فوري", "expected": "RED (hard fact)" },
  { "id": "structuring", "name": "تجزئة المعاملات (Structuring/Smurfing)", "description": "4 تحويلات تحت الحد لنفس المستفيد → AML RED", "expected": "RED (AML + classifier)" },
  { "id": "clean", "name": "تحويل عادي (Clean)", "description": "مستفيد معتمد، حساب قديم → موافقة", "expected": "GREEN" }
]
```

`GET /api/demo/scenarios/{id}` (`id` ∈ `scam | mule | structuring | clean`) returns a complete `TransferIntentRequest` body — the exact same JSON shape documented above — ready to `POST` straight to `/api/transfer-intent`. Use this to prefill your demo panel instead of duplicating the signal values.

---

### The seeded customer and beneficiaries (loaded from `/api/customer`)

The paying customer is **`ياسر العوفي` / `cust_8842`**, IBAN `SA..8842`, verified balance **8500.0 SAR**, with the payee list and statement shown in the `GET /api/customer` block above. **Load it at runtime — don't invent names.** These are the ten **real** beneficiary IBANs and the decision each one drives when sent with benign signals; the demo-controls panel must prefill by these exact strings (or better, just call `/api/demo/scenarios/{id}` for the 4 canonical ones).

| IBAN | Name (from `/api/customer`) | Saved | Age | Transit | Mule | Expected decision | Why |
|---|---|---|---|---|---|---|---|
| `SA0000000000000000001111` | خالد العتيبي | ✓ | 800d | 0.10 | no | **GREEN** | Known payee, old, low transit → triage CLEAR. |
| `SA0000000000000000004444` | عبدالله المطيري | ✓ | 600d | 0.08 | no | **GREEN** | Established landlord → CLEAR. |
| `SA0000000000000000005555` | متجر سلة | ✓ | 300d | 0.20 | no | **GREEN** | Trusted merchant → CLEAR. |
| `SA0000000000000000000000` | شركة الرياض للتقنية | ✓ | 1200d | 0.05 | no | **GREEN** | Salary/employer, known payee. |
| `SA0000000000000000006666` | سعد الدوسري | ✗ | 20d | 0.50 | no | **ORANGE** | New friend → escalates; **clears to GREEN** with a genuine self-sourced answer. |
| `SA0000000000000000008888` | Ahmed Trading LLC | ✗ | 15d | 0.45 | no | **ORANGE** | Unusual international payee → talk to the customer. |
| `SA0000000000000000002222` | عبدالعزيز الشمري | ✗ | 3d | 0.85 | no | **ORANGE** | New + high transit; must *talk* — coached/scam-language answers → RED. |
| `SA0000000000000000003333` | سلطان الغامدي | ✗ | 10d | 0.90 | **yes** | **RED** | On the mule watchlist → deterministic block, AI not even consulted. |
| `SA0000000000000000007777` | ماجد القحطاني | ✗ | 5d | 0.95 | **yes** | **RED** | Second mule → deterministic block. |
| `SA0000000000000000009999` | مؤسسة الفجر التجارية | ✗ | 8d | 0.88 | no | **RED** | AmlAgent structuring detector: the customer already has 4 same-day sub-threshold transfers to this exact IBAN in their history (see `/api/customer`) → another transfer here trips AML, not the fraud agent. This is the **only scenario driven by `AmlAgent` rather than `FraudAgent`** — a good one to show `agents[]` with a different agent name firing. |

> Only mules go RED on the account alone (aside from the structuring IBAN, which goes RED via AML pattern detection over existing history, not a hard account fact). Every other RED comes from a **failed/coached ORANGE conversation** — never from an IBAN merely being new. A validly-formatted IBAN that isn't seeded is treated as a neutral established-looking account (age 365, low transit, not a mule): benign signals → GREEN, suspicious signals → at most ORANGE.

> To reliably reproduce GREEN on the trusted payees, send benign signals (all `typed`, no call, no remote app, `maxIdleGapMs` small, `purposeFilledAfterIbanMs` ≥ 1500, low behavioral values) **and** an `amount` ≤ 4250 (the backend escalates when `amount > 0.5 × balance`, i.e. > 4250 for this customer). For ORANGE/RED, the account facts (new+transit / mule / structuring history) dominate regardless of signals.

---

## 1. What to build

A fake **mobile banking app clone** (neutral name, e.g. «بنك المدى / Bank XYZ») that demonstrates Shield end to end. Render it as a **phone frame centered on the page**, mobile-first, **Arabic-first and RTL** (`dir="rtl"` on the root). It's a prototype demo, not a real product.

## 2. Tech stack

React + Vite + TypeScript, Tailwind CSS, plain React state/context (no Redux), `fetch` to the backend. Base URL from `VITE_API_BASE` (default `http://localhost:8080`). No auth. Keep it a small set of components. Run the dev server on **port 5173** (or 8080 — both are CORS-allowed).

## 3. The flow it must exercise

- **On load**: `GET /api/customer` and drive the whole app from it (name `ياسر العوفي`, balance, saved payees, statement). Don't hardcode any of this.
- **Home**: total balance (8500.00 SAR for `cust_8842`), the customer's name/IBAN, a recent-transactions list from `transactions`, and a تحويل (Transfer) button.
- **Transfer / add-beneficiary screen**: a saved-payees picker (the `saved: true` beneficiaries) plus a "new payee" path (enter/paste an IBAN — used for the ORANGE/RED demos), an amount, and a purpose (preset dropdown + free text). On submit → `POST /api/transfer-intent` with the full signals payload (exact shape above).
- **GREEN** → smooth success ("جارٍ التحويل / تمت الإضافة"), show `message`.
- **ORANGE** → transition into the intervention chat (section 5), seeded from `session`.
- **RED** → a firm block screen: the AI `message` + awareness text + `reportReference`, plus the `lesson` card (scam name, 3 signs, one action) — this is a required screen element, not optional polish.
- Handle the **400 INVALID_IBAN** case: show an inline field error ("رقم آيبان غير صالح") using the returned `message`; do not crash or route away.

## 4. Signal capture — must be real, not faked

The frontend must actually collect what it sends; this is core to the concept. Capture and send in the `signals` object:

- `ibanEntryMethod`: detect paste vs typing on the IBAN field (`onPaste` → `"pasted"`, keystrokes only → `"typed"`).
- `purposeEntryMethod` + `purposeFilledAfterIbanMs`: same paste detection on the purpose field, plus the milliseconds between finishing the IBAN and finishing the purpose.
- `maxIdleGapMs`: track interaction timestamps on the transfer screen; report the longest gap with no input.
- `cadenceDeviation` / `deviceInconsistency`: a rough keystroke-timing estimate if feasible; otherwise a scenario preset — **state in the UI/README that these are approximated for a web demo**. `cadenceSamples` = number of keystroke intervals measured. `baselineSource` = `"personal"` if you have a baseline, else `"population"`.
- `activeCallDetected` / `remoteAccessAppDetected`: not detectable in a browser → expose as **toggles in the demo-controls panel** (section 8) and send whatever they're set to.

Remember: the backend ignores any server-fact you try to send; only these client signals + the IBAN/amount/purpose actually matter.

## 5. The intervention chat — the centerpiece

When `/transfer-intent` returns ORANGE with a `session`, open a full-screen **secure conversation** view:

- RTL chat bubbles: AI (Shield) messages on one side, the user's replies on the other, with a text input + send button.
- Seed the first bubble with `session.question`. On send → `POST /api/intervention/{sessionId}/reply` with `{ "answer": "<text>" }`.
  - `action: "ASK"` → append the returned `message` as a new Shield bubble and keep the conversation open.
  - `action: "RESOLVED"` → route to the cleared (GREEN) or blocked (RED) screen per `finalDecision`, surfacing the final `message`.
- Surface the AI's warning/awareness text prominently.
- **Design intent:** this screen should feel calm and deliberately *slower* than the rest of the app — unhurried transitions, breathing room, a typing-indicator pause before each Shield reply — because the whole point is breaking the scammer's manufactured urgency. Make the pace itself part of the message.

## 6. Judge-facing intelligence view

A separate route `/intel` (same aesthetic, more dashboard-like) with three live panels:

- **Mission-control / agents panel** — the `agents[]` array from the *last* `/api/transfer-intent` call (keep it in state after each assessment). Render each agent as a card: name, decision, evidence. If `trace` is present, make the card expandable into a step-by-step "chain of thought" (THOUGHT → TOOL_CALL → TOOL_RESULT → ... → FINAL_ANSWER), each step showing `toolName`/`content`/`durationMs`. Skip or visually de-emphasize the two `"Agent"` / `_READY` placeholder entries described above. This is the best visual proof of the multi-agent architecture — worth the most design attention here.
- **Fraud-intelligence feed** ← `GET /api/fraud-intel`: render each record's `decision`, `outcome`, `beneficiaryIban`, `timestamp`. Frame it as "labeled social-engineering data feeding national / GCC fraud intelligence." Newest at top (reverse the array for display). If a record has a non-null `trace`, let judges click into it too (same renderer as the mission-control panel) — this is how a past decision's reasoning gets "returned alongside the history."
- **Scam-script playbook** ← `GET /api/scam-playbook`: list the Arabic pattern strings so judges watch the list grow right after a coached intervention.

Add a **refresh button** and/or a short poll (e.g. every 3–5s) so all panels update live during the demo.

## 7. Aesthetic — minimalist "ink on paper"

Beige and black, editorial, printmaking feel. No drop shadows, no glassmorphism, no decorative gradients.

- **Palette:** paper background `#EDE8DC`, ink text `#1A1712`, hairline borders = ink at low opacity. State accents desaturated and earthy, used sparingly: cleared/green `#3A5A40`, caution/orange `#B07A2E` (ochre), block/red `#7A2E2E` (oxblood).
- **Texture:** very subtle paper grain; hairline rules instead of boxed cards where possible.
- **Type:** Arabic-first pairing — an editorial Arabic face (Amiri or Noto Naskh Arabic) for headings, a clean Arabic UI face (IBM Plex Sans Arabic or Tajawal) for body; tabular numerals for balances. Generous whitespace, single column, large touch targets.
- **Motion:** minimal and purposeful; the ORANGE transition slow and deliberate.

## 8. Demo controls — bulletproof the live demo

A discreet panel (long-press the logo, or a `?demo` query param) to:

- Pick a scenario from `GET /api/demo/scenarios` and prefill the transfer form from `GET /api/demo/scenarios/{id}` — covering all four outcomes:
  - **GREEN**: `clean` scenario, or any saved payee (`1111`, `4444`, `5555`, `0000`).
  - **ORANGE**: `scam` scenario (`SA...2222`, coached → RED), or `SA...6666` (new friend → clears to GREEN), or `SA...8888` (international).
  - **RED**: `mule` scenario (`SA...3333` / `SA...7777`), or `structuring` scenario (`SA...9999` — the AML path, not the fraud path).
- Toggle `activeCallDetected` and `remoteAccessAppDetected`.
- Reset frontend state.

So on stage you select a scenario and go — no typing IBANs live. (Prefilling still routes through the real signal-capture code; you can also present it as "pasted" to demonstrate the coercion signal.)

## 9. Config & run

- `.env` with `VITE_API_BASE=http://localhost:8080`.
- `npm run dev` on **port 5173** (set `server.port` in `vite.config.ts` or use `--port 5173`).
- A README with run steps and a demo walkthrough that runs the four scenarios in order (below).

## 10. Scenario walkthrough (the exact demo, against the live backend)

Start the backend (`./mvnw spring-boot:run`, boots on `:8080`; with no API key it runs fully deterministic via `StubShieldAi`), then:

1. **GREEN** — transfer to `SA0000000000000000001111`, amount `100`, purpose typed, all benign signals → response `decision: "GREEN"`, `action: "PROCEED"`. Show the success screen. Check `agents[]` — `FraudAgent`/`AmlAgent` should both be GREEN.
2. **ORANGE → RED (coached victim)** — transfer to `SA0000000000000000002222`, amount `900`, purpose `"مصاريف عائلية"` pasted, `activeCallDetected: true` → response `decision: "ORANGE"` with a `session`. Open the chat. Reply `"مصاريف عائلية"` → `action: "ASK"`; reply `"مصاريف عائلية"` again → `action: "ASK"` (a second, different follow-up); reply `"مصاريف عائلية"` a third time → `action: "RESOLVED"`, `finalDecision: "RED"`. Route to the block screen — this response now carries `sar`/`lesson`, show them.
3. **ORANGE → GREEN (real customer)** — repeat step 2 to get a fresh ORANGE `session`, but reply once with a detailed, self-sourced answer, e.g. `"هذا حساب صديقي خالد الذي أعرفه منذ سنوات وأسدد له ثمن سيارة اشتريتها منه"` → `action: "RESOLVED"`, `finalDecision: "GREEN"`. *(One convincing reply → GREEN. Use a new session — a resolved session is closed.)*
4. **RED (mule)** — transfer to `SA0000000000000000003333` → immediate `decision: "RED"`, `action: "BLOCK_AND_REPORT"`, with a `reportReference`, `sar`, `lesson`. No chat; the mule is a deterministic block.
5. **RED (structuring, AML path)** — transfer to `SA0000000000000000009999` (the `structuring` demo scenario) → `decision: "RED"` again, but this time look at `agents[]`: this is the scenario meant to showcase `AmlAgent` catching a pattern `FraudAgent` isn't designed to detect (repeated sub-threshold transfers), so it's a good moment to show the mission-control panel distinguishing which specialist agent's evidence is the structuring/smurfing one — both agents may report a verdict, but the AML evidence text is the one that names the pattern.
6. Open `/intel` and refresh: the **fraud-intel feed** now shows the ORANGE/RED/GREEN outcomes from the runs above, the **playbook** shows the seeded three patterns plus any freshly-learned coaching pattern, and the **mission-control panel** reflects the last assessment's per-agent verdicts.

## 11. Done when

The phone-framed banking app runs on `:5173`; all four seeded scenarios (clean, scam, mule, structuring) work end-to-end against the live backend; ORANGE opens the RTL chat and resolves to GREEN or RED based on the conversation; RED shows the `lesson` card; the 400 invalid-IBAN path shows a clean field error; and `/intel` shows the fraud-intel feed, the playbook, and the per-agent mission-control panel (with expandable reasoning traces when present) all populating live.
