# Shield (درع) — Multi-Agent Financial Crime Platform

## Prerequisites

1. **JDK 25** (required — won't compile with older versions)
   - Download: https://adoptium.net/temurin/releases/?version=25
   - Verify: `java -version` should show `25.x`

2. **Groq API Key** (free tier — runs Llama 3.3 70B at 500 tokens/s)
   - Get one at https://console.groq.com/keys
   - Or use local Ollama instead (see below)

## Quick Start

```bash
# Set environment variables
export SHIELD_AI=groq
export GROQ_API_KEY=your_key_here
export GROQ_MODEL=llama-3.3-70b-versatile

# Run (Maven downloads everything automatically)
./mvnw spring-boot:run
```

Open http://localhost:8080 — the full app (frontend + backend + agents) is on one port.

## Demo Scenarios

Click the "تجريبي" button (bottom-left) to pick a scenario:

| Scenario | Expected | What happens |
|---|---|---|
| أخضر · مستفيد موثوق | GREEN | Clean transfer to known payee |
| برتقالي · ضحية مُوجَّهة | ORANGE | Coercion signals + new account → intervention chat |
| أحمر · حساب مُمرِّر | RED | Mule watchlist hit → instant block |
| أحمر · تجزئة (AML) | RED | 4 sub-threshold transfers → AML agent fires |

On RED/ORANGE, the Mission Control panel shows each agent's reasoning chain — every tool call, every result, every thought.

## Architecture

### Agents (real ReAct loop, not chatbots)

| Agent | Tools | What it does |
|---|---|---|
| **FraudAgent** | 7 tools (mule watchlist, account info, customer history, known payee, AML score, scam patterns, fraud intel) | Investigates fraud + social engineering |
| **AmlAgent** | 7 tools (structuring, account risk, round-tripping, cross-border, ONNX classifier, transaction history, fraud intel) | Investigates money laundering patterns |
| **ComplianceAgent** | SAR draft generation | Auto-drafts Suspicious Activity Report on RED |
| **LiteracyAgent** | LLM-powered lesson generation | Just-in-time Arabic micro-lesson on RED |

### AgentHarness

The `AgentHarness` runs a genuine ReAct (Reason → Act → Observe) loop:
1. LLM receives the task + tool specs
2. LLM decides which tools to call
3. Harness executes tools, feeds results back
4. LLM reasons about results, calls more tools or gives final answer
5. Every step is captured in an `AgentTrace` — visible in the UI

### AML Classifier

- Trained RandomForest model (22 features, ONNX format)
- ROC-AUC: 0.983
- Trained on synthetic data (structuring, rapid transit, round-tripping)
- Located at `src/main/resources/models/aml_classifier.onnx`

## Alternative: Local Ollama

If you prefer local inference (slower on CPU):

```bash
# Install Ollama: https://ollama.com
ollama pull qwen2.5:7b-instruct

export SHIELD_AI=ollama
export OLLAMA_MODEL=qwen2.5:7b-instruct
./mvnw spring-boot:run
```

## API Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/transfer-intent` | POST | Multi-agent assessment |
| `/api/intervention/{id}/reply` | POST | Continue intervention chat |
| `/api/customer` | GET | Demo customer data |
| `/api/demo/scenarios` | GET | List demo scenarios |
| `/api/demo/scenarios/{id}` | GET | Get scenario request body |
| `/api/fraud-intel` | GET | Grey-zone intel feed |
| `/api/scam-playbook` | GET | Self-learning scam patterns |
| `/open-banking/v1/fraud-intel` | POST | Contribute to fraud network |
| `/open-banking/v1/accounts/{hash}/reputation` | GET | Query account reputation |
| `/open-banking/v1/network/overview` | GET | SAMA regulator view |
