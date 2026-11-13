# AgentForge

AgentForge is a multimodal decision-intelligence platform. Six AI agents analyse a problem together, debate it and explain how they reached their recommendation. The backend is pure Java (Spring Boot 3.5). The frontend is plain HTML, CSS and JavaScript.

## Run it

```bat
run.cmd
```

Then open http://localhost:8080 and create an account.

- **AI mode (recommended):** set `ANTHROPIC_API_KEY` before starting. The agents then reason with Claude (`claude-opus-5-5` by default; change it with `AGENTFORGE_MODEL`), and uploaded images and scanned PDFs are analysed with Claude vision.
- **Rule-based mode:** if no key is set, the agents use deterministic Java rules. The UI labels every result produced this way.
- **OCR:** if [Tesseract](https://github.com/UB-Mannheim/tesseract/wiki) is installed and on `PATH` (or `TESSERACT_CMD` is set), images are OCR'd locally as well.

> This folder lives at `C:\AgentForge` because the Windows user name contains `!`, which breaks Java jar URLs. A local copy of Maven is in `.tools`, and its repository is in `.home`. Use `./mvn.sh <goal>` from Git Bash.

Build and test:

```bash
./mvn.sh test       # end-to-end workflow test on the rule-based engine
./mvn.sh package    # builds target/agentforge-2.0.0.jar
```

## Architecture (Java OOP)

| Package | Responsibility |
|---|---|
| `agent` | `Agent` (abstract template method: Claude or rules) → `SpecialistAgent` → `AnalystAgent`, `InspectorAgent`, `RiskAgent`; plus `CoordinatorAgent`, `CriticAgent`, `SynthesizerAgent`; `AgentTeam` registry; `AgentContext` shared case file |
| `workflow` | `Orchestrator`: plan → parallel specialists → critic → debate/revisions → human checkpoints → synthesis; What-If re-runs; pause/stop/resume |
| `events` | `EventService`: persists every event (the decision trace) and streams it over Server-Sent Events |
| `ai` | `AiService`: Claude API through the official Java SDK (with a server-side refusal fallback) |
| `evidence` | OCR (Tesseract CLI), PDFBox, DOCX reader and Claude vision; user corrections; rejecting evidence |
| `memory` | Per-user memory vault with keyword retrieval, plus a record of each memory's reuse |
| `insight` | Version comparison, the Visual Thinking Board graph, and Markdown/HTML reports |
| `geo` | Optional geocoding and nearby search (OpenStreetMap Nominatim), used only for location-dependent tasks |
| `auth` | Accounts (PBKDF2), session auth, per-user isolation on every query |

Data is stored in an embedded H2 database under `./data`.

## Features → where to find them

- **Voice-to-Agent:** New Analysis → 🎤 (Web Speech API). The transcript can be edited before submitting, and "🔊 Read aloud" on the recommendation reads it out.
- **Vision Intelligence:** evidence upload, extracted text you can correct, Claude visual analysis, and an Inspector that flags inconsistencies between your description and the evidence.
- **Live Agent Radar:** task → Agent Radar. It is built only from backend events; "Replay" re-plays the recorded events.
- **Memory Vault:** sidebar. Inspect, edit, add and delete memories. Each task's Overview shows which memories it reused.
- **What-If Simulator:** task → What-If. Edit assumptions, run a new version and compare (optionally with an AI explanation).
- **Visual Thinking Board:** evidence map, mind map, decision tree and agent workflow. Click nodes to explore them; export as PNG or SVG.
- **Human-in-the-loop:** checkpoints chosen at creation, "Pause at next checkpoint", conflict decisions, and adding evidence while paused. All decisions are recorded.
- **Decision Trace:** every event, grouped by version and filterable, plus a version comparison.
- **Context Intelligence:** optional location (typed, or captured once from the device after permission). Used only when the Coordinator judges the task location-dependent, and excluded from reports otherwise.
- **UX:** global search (`/`), notifications (live over SSE), keyboard shortcuts (`?`), report downloads (MD/HTML/JSON), previews, and responsive layout.

## More features

- **Ask the Agents:** a chat tab on every task. Answers come only from that analysis and cite findings and evidence as clickable chips. You can ask by voice and have answers read aloud.
- **Insights:** analytics across all your analyses, including findings by agent, what happened to each finding, confidence over time, and conflict outcomes.
- **Scenario templates and live demo:** five ready-made scenarios. "⚡ Live demo" creates a real task with sample evidence and starts the agents straight away.
- **Debate intensity:** Gentle, Balanced or Ruthless, which controls how hard the Devil's Advocate pushes back.
- **Command palette:** `Ctrl+K` jumps to any page or task, runs actions and launches demos.

## Learning the app

Click **▶ Tutorial** in the top bar, or **Help & Tutorial** in the sidebar. A narrated walkthrough plays inside the app like a video: the cursor moves and clicks, each feature is spotlighted and explained, and a voice reads the captions aloud. There are 16 chapters. Controls: Space to pause, ← → to skip, Esc to exit, ☰ to choose a chapter, and 1×/1.5×/2× for speed. The Help page also lists written steps for every feature, with a ▶ Watch button for each chapter.

## Deploy to the cloud (Render)

The repo includes a `Dockerfile` (Java 21 with Tesseract OCR) and a `render.yaml` blueprint.

1. Sign in at https://render.com with GitHub.
2. Click **New +** → **Blueprint**, pick this repository, and click **Apply**.
3. When asked, fill in:
   - `AGENTFORGE_SIGNUP_CODE`: a secret invite code. Only people with this code can create accounts. Strongly recommended for a public URL.
   - `ANTHROPIC_API_KEY`: optional. Enables Claude. Anyone who signs up uses your key, which costs you money.
4. Wait for the build (about 5–10 minutes the first time), then open the `https://agentforge-xxxx.onrender.com` URL.

On the free plan the app sleeps after 15 minutes without visitors (the first visit then takes about a minute), and data is reset on every deploy. To keep data, use a paid plan and the `disk:` block in `render.yaml`.

Other settings: `AGENTFORGE_DATA_DIR` (data folder), `AGENTFORGE_MODEL`, `AGENTFORGE_EFFORT`, `AGENTFORGE_GEO=false` (turns off maps and geocoding), `PORT`.
