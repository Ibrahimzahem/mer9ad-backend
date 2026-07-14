# Prompt — hand this to the Shield BACKEND Claude Code agent

You've built the Shield backend. Now write a single markdown file **`frontend-spec.md`** at the repo root, addressed to a **separate frontend Claude Code agent**, that fully specifies a demo frontend for this exact backend.

**Critical — ground it in the real API.** Before writing, inspect your own controllers, DTOs, and seed data. The spec must use the ACTUAL endpoint paths, the exact request/response field names and types, and the REAL seeded account IBANs together with the decision each one triggers (Green / Orange / Red). No placeholders — embed real example request/response JSON taken from your implementation. Also ensure the backend allows **CORS from `http://localhost:5173`** (add a CORS config if it isn't there) and note it in the spec, otherwise the frontend can't call the API.

Put an **"Actual API contract"** section at the very top of `frontend-spec.md` (real endpoints, real payload shape, real seeded IBANs + expected outcome for each), so the frontend agent never guesses. Then include everything below.

## 1. What to build
A fake **mobile banking app clone** (neutral name, e.g. «بنك المدى / Bank XYZ») that demonstrates Shield end to end. Render it as a **phone frame centered on the page**, mobile-first, **Arabic-first and RTL** (`dir="rtl"`). It's a prototype demo, not a real product.

## 2. Tech stack
React + Vite + TypeScript, Tailwind CSS, plain React state/context (no Redux), `fetch` to the backend. Base URL from `VITE_API_BASE` (default `http://localhost:8080`). No auth. Keep it a small set of components.

## 3. The flow it must exercise
- **Home**: total balance + accounts + a تحويل (Transfer) button, mirroring the seeded customer (`cust_8842`).
- **Transfer / add-beneficiary screen**: inputs for beneficiary IBAN, amount, and purpose (preset dropdown + free text). On submit → `POST /api/transfer-intent` with the full signals payload (exact shape from the backend).
- **Green** → smooth success ("جارٍ التحويل / تمت الإضافة").
- **Orange** → transition into the intervention chat (section 5).
- **Red** → a firm block screen: the AI warning + awareness message + report reference.

## 4. Signal capture — must be real, not faked
The frontend has to actually collect what it claims; this is core to the concept. Capture and send:
- `ibanEntryMethod`: detect paste vs typing on the IBAN field (`onPaste` → "pasted", keystrokes → "typed").
- `purposeEntryMethod` + `purposeFilledAfterIbanMs`: same paste detection, plus the ms between completing the IBAN and completing the purpose.
- `maxIdleGapMs` + time-on-screen: track interaction timestamps; the longest gap with no input.
- `cadenceDeviation` / `deviceInconsistency`: a rough keystroke-timing value if feasible, otherwise a scenario preset — and state in the spec that these are approximated for a web demo.
- `activeCallDetected` / `remoteAccessAppDetected`: not detectable in a browser → expose them as toggles in the demo-controls panel (section 8) and send whatever they're set to.

## 5. The intervention chat — the centerpiece
When `/transfer-intent` returns ORANGE with a `session`, open a full-screen **secure conversation** view:
- RTL chat bubbles: AI messages on one side, user replies on the other, a text input + send button.
- Seed it with the AI's first `question`. On send → `POST /api/intervention/{sessionId}/reply` with `{ answer }`. If `action: ASK` → append the returned `message` and continue. If `action: RESOLVED` → route to the cleared (Green) or blocked (Red) screen per `finalDecision`.
- Surface the AI's warning/awareness text prominently.
- **Design intent:** this screen should feel calm and deliberately *slower* than the rest of the app — unhurried transitions, breathing room — because the whole point is breaking the scammer's manufactured urgency. Make the pace itself part of the message.

## 6. Judge-facing intelligence view — shows the two visionary features
A separate route `/intel` (same aesthetic, more dashboard-like) with two live panels:
- **Fraud-intelligence feed** ← `GET /api/fraud-intel`: labeled grey-zone outcomes (decision, outcome, signals). Frame it as "labeled social-engineering data feeding national / GCC fraud intelligence."
- **Scam-script playbook** ← `GET /api/scam-playbook`: known + newly-learned manipulation patterns, so judges watch it grow right after an intervention.
  Add a refresh button or short poll so both update live during the demo.

## 7. Aesthetic — minimalist "ink on paper"
Beige and black, editorial, printmaking feel. No drop shadows, no glassmorphism, no decorative gradients.
- **Palette:** paper background `#EDE8DC`, ink text `#1A1712`, hairline borders = ink at low opacity. State accents desaturated and earthy, used sparingly: cleared/green `#3A5A40`, caution/orange `#B07A2E` (ochre), block/red `#7A2E2E` (oxblood).
- **Texture:** very subtle paper grain; hairline rules instead of boxed cards where possible.
- **Type:** Arabic-first pairing — an editorial Arabic face (Amiri or Noto Naskh Arabic) for headings, a clean Arabic UI face (IBM Plex Sans Arabic or Tajawal) for body; tabular numerals for balances. Generous whitespace, single column, large touch targets.
- **Motion:** minimal and purposeful; the Orange transition slow and deliberate.

## 8. Demo controls — bulletproof the live demo
A discreet panel (long-press the logo, or a `?demo` query param) to: pick which seeded beneficiary to prefill (Green / Orange / Red by real IBAN), toggle `activeCallDetected` and `remoteAccessAppDetected`, and reset state. So on stage you select a scenario and go — no typing IBANs live.

## 9. Config & run
`.env` with `VITE_API_BASE`; `npm run dev` on port 5173. A README with run steps and a demo walkthrough that runs the three scenarios in order.

## 10. Done when
The phone-framed banking app runs; all three seeded scenarios work end-to-end against the live backend; Orange opens the RTL chat and resolves to Green or Red based on the conversation; and `/intel` shows both feeds populating after interventions.

---

Write `frontend-spec.md` as a complete, self-contained document — the frontend agent will only have that file and the running backend, not this conversation.