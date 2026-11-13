// The tutorial script: one chapter per feature. Every step drives the real application.
import { closeModal } from '../core.js';

const json = (url, body) => fetch('/api' + url, {
  method: body ? 'POST' : 'GET', credentials: 'same-origin',
  headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined,
}).then(async (r) => { const d = await r.json().catch(() => ({})); if (!r.ok) throw new Error(d.error || r.status); return d; });

const taskRoute = (tab) => (ctx) => `#/task/${ctx.taskId}/${tab}`;

/** Make sure the tutorial has a demo task to show; launch one quietly if needed. */
async function ensureTask(ctx) {
  if (ctx.taskId) {
    try { await json(`/tasks/${ctx.taskId}`); return; } catch { ctx.taskId = null; }
  }
  const r = await json('/templates/product-launch/launch', {});
  ctx.taskId = r.taskId;
}

/** Wait for the run to settle; approve any checkpoint automatically (used when a chapter is played on its own). */
async function ensureCompleted(ctx, sleep) {
  await ensureTask(ctx);
  for (let i = 0; i < 90; i++) {
    const d = await json(`/tasks/${ctx.taskId}`);
    if (d.task.status === 'completed') return d;
    if (['failed', 'stopped', 'interrupted'].includes(d.task.status)) { await json(`/tasks/${ctx.taskId}/run`, {}); }
    else if (d.gate) {
      const choices = {};
      (d.gate.payload?.conflicts || []).forEach((c) => (choices[c.id] = 'uphold'));
      await json(`/tasks/${ctx.taskId}/decision`, { action: 'approve', conflictChoices: choices });
    }
    await sleep(1000);
  }
  throw new Error('The demo analysis did not finish in time');
}

const parentCard = (sel) => () => document.querySelector(sel)?.closest('.card');

export const CHAPTERS = [
  {
    id: 'welcome', icon: '👋', title: 'Welcome & Command Center',
    steps: [
      { route: '#/dashboard', title: 'Welcome to AgentForge', text: 'Welcome! This tutorial plays by itself, like a video. Watch the cursor and the highlighted areas. Press Space to pause, the arrow keys to skip, or Escape to exit at any time.' },
      { target: '#sidebar .nav', title: 'The sidebar', text: 'The sidebar takes you to every main page: the Command Center, New Analysis, the Conflict Center, the Memory Vault, Insights, Services and this Help.' },
      { target: '.view .grid.g4', title: 'Your numbers at a glance', text: 'The Command Center shows how many analyses you have, how many are completed, how many conflicts still need you, and how many memories the agents have stored.' },
      { target: parentCard('.view .badge'), title: 'Meet the six agents', text: 'These are your six AI agents. The Coordinator plans. The Research Analyst finds facts. The Evidence Inspector checks your files. The Risk Assessor looks for danger. The Devil\'s Advocate argues against weak ideas. The Synthesizer writes the final recommendation.' },
      { target: '#engineBadge', title: 'AI or rule-based', text: 'This badge tells you which engine is active. Claude connected means real AI reasoning. Rule-based mode means no API key is set, so the agents use simpler Java rules, and every result is clearly labelled.' },
      { target: '#bellBtn', title: 'Notifications', text: 'The bell lights up when an analysis finishes, or when the agents need your decision on a conflict.' },
      { target: '#searchInput', title: 'Search everything', text: 'Search finds tasks, findings, memories and evidence. Press the slash key to jump here.' },
      { target: '#motionBtn', title: 'Live wallpaper', text: 'This star turns the animated background on or off. The background pulses with each agent\'s colour whenever real agent activity happens.' },
    ],
  },
  {
    id: 'demo', icon: '⚡', title: 'Launch a live demo',
    steps: [
      { route: '#/dashboard', target: '.page-head button::Live demo', title: 'Live demo button', text: 'The quickest way to see AgentForge work is a live demo. Click the Live demo button.', action: (a, el) => a.click(el) },
      { target: '.tpl::Product launch readiness', title: 'Pick a scenario', text: 'Choose a ready-made scenario. Each one creates a real analysis with sample evidence. We will launch Product launch readiness.',
        action: async (a, el) => { await a.click(el); await a.waitFor(() => /#\/task\/\d+/.test(location.hash)); a.ctx.taskId = Number(location.hash.match(/task\/(\d+)/)[1]); } },
      { target: '.radar svg', title: 'The Live Agent Radar', text: 'This is the Live Agent Radar. Each circle is an agent. The ring colour shows its state: blue is working, amber is reviewing, purple is waiting, green is done. Moving dots are real messages between agents.', hold: 7000 },
      { target: '.radar .legend', title: 'Reading the colours', text: 'Red dashed lines are conflicts, when the Devil\'s Advocate objects to a finding. Green lines are revisions, when an agent fixes its finding after an objection.' },
      { target: '.feed', title: 'Event stream', text: 'On the right, the event stream lists every step the agents took, live from the Java backend. Nothing here is faked: it is the actual work being done.' },
      { target: '.radar button::Replay', title: 'Replay', text: 'After a run finishes, Replay recorded run plays the whole debate again. That is great for presentations.' },
    ],
  },
  {
    id: 'approval', icon: '⏸', title: 'Approve conflicts (human in the loop)',
    steps: [
      { before: async (a) => { await ensureTask(a.ctx); await a.waitFor(async () => ['awaiting_approval', 'completed'].includes((await json(`/tasks/${a.ctx.taskId}`)).task.status)); },
        skip: async (a) => { if (!a.ctx.taskId) return false; const d = await json(`/tasks/${a.ctx.taskId}`).catch(() => null); return d?.task.status === 'completed'; },
        route: taskRoute('overview'), target: '.gate', title: 'Your approval is needed', text: 'When agents disagree and cannot settle it themselves, the analysis pauses and asks you. This yellow panel lists each conflict: the finding, and the Devil\'s Advocate\'s objection.' },
      { skip: async () => !document.querySelector('.gate'), target: '.gate input[value=reject]', title: 'Decide each conflict', text: 'For each conflict, choose Uphold to keep the finding, or Accept the objection to reject it. Here the budget in the description does not match the finance memo, so we accept the objection.',
        action: async (a) => {
          const cards = [...document.querySelectorAll('.gate .card')];
          for (const [i, card] of cards.entries()) await a.click(card.querySelector(`input[value=${i === 0 ? 'reject' : 'uphold'}]`));
        } },
      { skip: async () => !document.querySelector('.gate'), target: '.gate textarea', title: 'Guide the agents', text: 'You can also write guidance for the agents. They read it before writing the recommendation.',
        action: (a, el) => a.type(el, 'The finance memo has the correct budget and survey numbers.') },
      { skip: async () => !document.querySelector('.gate'), target: '.gate button::Approve', title: 'Resume', text: 'Click Approve and resume. Your decision is recorded in the task history, and the agents continue.',
        action: async (a, el) => { await a.click(el); await ensureCompleted(a.ctx, a.sleep); } },
    ],
  },
  {
    id: 'result', icon: '✦', title: 'Read the recommendation & reports',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('overview'), target: '.rec-card', title: 'The recommendation', text: 'Here is the final recommendation from the Synthesizer, with a confidence score. It weighs every finding, risk, conflict and your decisions.' },
      { target: '.rec-card ol', title: 'Why: the reasoning', text: 'Every reason is listed. Purple finding links jump straight to the evidence behind that reason.' },
      { target: '.rec-card button::Read aloud', title: 'Read aloud', text: 'Read aloud speaks the recommendation and its reasons. You can also press the R key.' },
      { target: '.view .grid.g3', title: 'Findings by agent', text: 'Below, you see what each specialist found.' },
      { target: '.page-head button::Report', title: 'Download a report', text: 'Report downloads the full analysis as Markdown, a printable HTML page you can save as PDF, or raw JSON data.',
        action: async (a, el) => { await a.click(el); await a.sleep(1800); document.body.click(); } },
    ],
  },
  {
    id: 'ask', icon: '💬', title: 'Ask the Agents',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('ask'), target: '.tab::Ask the Agents', title: 'Ask the Agents', text: 'After an analysis, you can question the agents in this chat. Answers come only from this analysis, never from guesses.' },
      { target: '.chat-input', title: 'Ask a question', text: 'Type any question. Let\'s ask what the evidence says about the budget.',
        action: async (a, el) => { await a.type(el, 'What does the evidence say about the budget?'); await a.click('.chat-compose button::Send'); await a.find('.chat-msg.them .chat-body:not(.typing)', 20000); } },
      { target: () => [...document.querySelectorAll('.chat-msg.them')].pop(), title: 'A grounded answer', text: 'The agent team answers with citations. Each small chip is a source: a finding or an evidence file.' },
      { target: '.cite', title: 'Click a citation', text: 'Clicking a citation jumps to that finding or evidence, so you can always check the proof.' },
      { target: '.chat-compose .btn.icon', title: 'Ask by voice', text: 'Use the microphone to ask by voice, and Read aloud under each answer to hear it.' },
    ],
  },
  {
    id: 'debate', icon: '⚔', title: 'Debate Room',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('debate'), target: '.finding', title: 'Findings', text: 'The Debate Room shows every finding in three columns: Research Analyst, Evidence Inspector and Risk Assessor. The bar shows confidence.' },
      { target: '.thread', title: 'The argument', text: 'Under a finding you see the argument: the Devil\'s Advocate\'s objection in red, the agent\'s reply, and your own decision if you made one.' },
      { target: '.finding button::Remember', title: 'Remember a finding', text: 'Remember saves a useful finding to your Memory Vault, so agents can reuse it in future tasks.' },
    ],
  },
  {
    id: 'evidence', icon: '📎', title: 'Evidence & vision',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('evidence'), target: '.dropzone', title: 'Upload evidence', text: 'Drop images, screenshots, charts, PDFs or Word files here. AgentForge reads the text with OCR or PDF parsing, and analyses images with Claude vision when an API key is set.' },
      { target: '.ev', title: 'What was extracted', text: 'Each file shows a preview, how it was read, and the extracted text.' },
      { target: '.ev textarea', title: 'Correct mistakes', text: 'If the extractor got something wrong, fix the text here and click Save correction. The agents use your corrected version.' },
      { target: '.ev button::Reject', title: 'Reject evidence', text: 'Reject removes a file from the analysis without deleting it. You can restore it later. Rejections are recorded in the history.' },
    ],
  },
  {
    id: 'whatif', icon: '⚗', title: 'What-If simulator',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('whatif'), target: 'input[aria-label="Assumption 1"]', title: 'Change an assumption', text: 'What-If lets you change the assumptions the analysis depends on. Let\'s change the first one to a smaller budget and a later launch.',
        action: (a, el) => a.type(el, 'We launch in 10 weeks with a budget of $32000.', { cps: 45 }) },
      { target: 'input[placeholder^="Name this scenario"]', title: 'Name the scenario', text: 'Give the scenario a name so you can find it later.', action: (a, el) => a.type(el, 'Smaller budget, later launch') },
      { target: 'button::Run scenario', title: 'Run it', text: 'Run scenario re-runs the agents as a new version. The original stays untouched.',
        action: async (a, el) => {
          const before = (await json(`/tasks/${a.ctx.taskId}`)).task.current_version;
          await a.click(el);
          await a.waitFor(async () => (await json(`/tasks/${a.ctx.taskId}`)).task.current_version > before);
          await ensureCompleted(a.ctx, a.sleep);
        } },
      { route: taskRoute('whatif'), target: '.card.tight.rec-card', timeout: 15000, title: 'Compare the versions', text: 'The comparison shows whether the recommendation changed, and how much the confidence moved.' },
      { target: '.diff', title: 'What changed and why', text: 'Below, each changed assumption and finding is listed, with the reason it changed. Yellow means changed, green means new, red means removed.' },
    ],
  },
  {
    id: 'board', icon: '⬡', title: 'Visual Thinking Board',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('board'), target: '.board-canvas', title: 'Evidence map', text: 'The Thinking Board turns the analysis into diagrams. The evidence map links evidence, to findings, to the recommendation. Green lines support, amber lines weigh against, red dashed lines are challenges.' },
      { target: '.board-node', title: 'Explore any node', text: 'Click any box to see its full explanation and its connections.', action: async (a, el) => { await a.click(el); await a.highlight('.node-panel'); } },
      { target: '.card.tight button::Mind map', title: 'Mind map', text: 'Mind map groups everything around the task.', action: (a, el) => a.click(el) },
      { target: '.card.tight button::Decision tree', title: 'Decision tree', text: 'Decision tree shows the options, when to choose each one, and the expected outcome. The recommended path is green.', action: (a, el) => a.click(el) },
      { target: '.card.tight button::PNG', title: 'Export', text: 'Export any diagram as a PNG or SVG image for slides or reports.' },
    ],
  },
  {
    id: 'trace', icon: '⏱', title: 'Decision Trace timeline',
    steps: [
      { before: (a) => ensureCompleted(a.ctx, a.sleep), route: taskRoute('timeline'), target: '.timeline', title: 'Full history', text: 'The Decision Trace records everything: plans, messages, findings, conflicts, revisions, evidence changes, and your decisions, grouped by version.' },
      { target: '.card button.btn.small::conflicts', title: 'Filter', text: 'Use the filters to see only conflicts, evidence, your actions, or memory.', action: (a, el) => a.click(el) },
      { target: () => document.querySelector('.tl-grid > .stack'), title: 'Reasoning and versions', text: 'On the right: the reasoning behind the recommendation, your decisions, and a version comparison.' },
    ],
  },
  {
    id: 'new', icon: '✚', title: 'Create your own analysis',
    steps: [
      { route: '#/new', target: '.tpl-strip', title: 'Start from a scenario', text: 'To analyse your own problem, open New Analysis. You can start from a scenario to fill in the form.' },
      { target: '.tpl-chip::Hiring decision', title: 'Fill from a template', text: 'Clicking Hiring decision fills in the title, description and settings.', action: (a, el) => a.click(el) },
      { target: '.mic', title: 'Speak instead of typing', text: 'Or click the microphone and simply speak your problem. Your words appear in the box, and you can edit them before continuing. This works in Chrome and Edge.' },
      { target: '.intensity button::Ruthless', title: 'Debate intensity', text: 'Debate intensity sets how hard the Devil\'s Advocate pushes back. Gentle raises only serious objections. Ruthless challenges everything.', action: (a, el) => a.click(el) },
      { target: '.check::When agents reach', title: 'Checkpoints', text: 'Checkpoints decide where the analysis pauses for your approval. You can also pause a running analysis at any time.' },
      { target: '.check::depends on a location', title: 'Optional location', text: 'Tick this only if your problem depends on a place. The location is used once, never tracked, and appears on a map in the results.' },
      { target: 'button::Continue to evidence', title: 'Continue', text: 'Then click Continue, add evidence files if you have any, and press Start analysis.' },
    ],
  },
  {
    id: 'memory', icon: '◈', title: 'Memory Vault',
    steps: [
      { route: '#/memory', target: () => document.querySelector('.view .card.tight button')?.closest('.card') || document.querySelector('.view .state'), title: 'What agents remember', text: 'The Memory Vault holds recommendations and strong findings from your past analyses. When a new task is similar, agents reuse them and show which ones they used.' },
      { target: '.page-head button::Add memory', title: 'You are in control', text: 'You can add your own notes, edit any memory, or delete it. Only you can see your vault.' },
    ],
  },
  {
    id: 'conflicts', icon: '⚔', title: 'Conflict Center',
    steps: [
      { route: '#/conflicts', target: '.view .tabs', title: 'Every objection in one place', text: 'The Conflict Center lists every objection across all your analyses. Filter by status: unresolved, resolved, or decided by you.' },
      { target: '.view .card.tight', title: 'Open the debate', text: 'Each card shows who objected to what, and how it ended. Click Open debate or Decide now to jump to it.' },
    ],
  },
  {
    id: 'insights', icon: '▤', title: 'Insights analytics',
    steps: [
      { route: '#/insights', target: '.view .grid.g4', title: 'Insights', text: 'Insights shows how your agent team performs across every analysis.' },
      { target: 'svg.chart', title: 'Charts', text: 'Findings by agent, what happened to each finding after the debate, confidence over time and conflict outcomes. Hover over any bar or point for details.' },
    ],
  },
  {
    id: 'power', icon: '⌘', title: 'Command palette & shortcuts',
    steps: [
      { route: '#/dashboard', before: async (a) => { closeModal(); }, setup: async (a) => { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true })); await a.sleep(500); },
        target: '.palette-input', title: 'Command palette', text: 'Press Control and K anywhere to open the command palette. Type to jump to any page or task, launch demos, or run actions.',
        action: (a, el) => a.type(el, 'insights', { cps: 10 }) },
      { setup: async (a) => { closeModal(); await a.sleep(300); document.getElementById('shortcutsBtn').click(); await a.sleep(400); },
        target: '.modal-card .kv', title: 'Keyboard shortcuts', text: 'Press the question mark key to see every shortcut. For example: N for a new analysis, slash for search, and numbers one to nine to switch tabs.', after: async () => closeModal() },
    ],
  },
  {
    id: 'services', icon: '◎', title: 'Services & turning on Claude',
    steps: [
      { route: '#/status', target: '.view .card', title: 'Services', text: 'Services shows what is connected: AI, OCR, PDF reading, maps and voice. Anything unavailable says so clearly, and explains how to turn it on.' },
      { target: '.view .list-item', title: 'Turn on Claude AI', text: 'To enable real AI reasoning and image understanding, close the app, then double-click start-with-claude dot cmd in the AgentForge folder, and paste your Anthropic API key.' },
    ],
  },
];
