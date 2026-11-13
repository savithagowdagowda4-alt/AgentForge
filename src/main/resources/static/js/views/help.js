import { h } from '../core.js';
import { playTutorial, CHAPTERS } from '../tour/index.js';

// Plain-language, step-by-step instructions for every feature. Each section can also be watched as a narrated tutorial chapter.
const GUIDE = {
  welcome: ['Use the sidebar on the left to move between pages.', 'The Command Center (home) shows your totals and your recent analyses. Click one to open it.',
    'The badge at the top right says “Claude connected” (AI on) or “Rule-based mode” (no API key).', 'The 🔔 bell shows notifications. The ✦ star turns the animated background on or off.'],
  demo: ['On the Command Center, click ⚡ Live demo.', 'Click any scenario (for example “Product launch readiness”).', 'You are taken to the Agent Radar: watch the six agents work, message each other and argue.',
    'Red dashed lines = conflicts. Green lines = revisions. The list on the right shows every real step.'],
  approval: ['If a yellow “Your approval is needed” panel appears on the Overview tab, the agents need you.', 'For each conflict choose “Uphold the finding” (keep it) or “Accept the objection” (reject it).',
    'Optionally type guidance for the agents, or click “Add or reject evidence first”.', 'Click ✓ Approve & resume. Your decision is saved in the history.'],
  result: ['Open the Overview tab of a finished analysis.', 'Read the ✦ Recommendation, its confidence and the “Why” list. Click a purple [finding] link to see its proof.',
    'Click 🔊 Read aloud (or press R) to hear it.', 'Click ⇩ Report to download Markdown, printable HTML (save as PDF from the browser) or JSON.'],
  ask: ['Open the “Ask the Agents” tab of a finished analysis.', 'Type a question and press Enter, or click 🎤 and speak.', 'Read the answer. Click the small chips (◎ finding, 📎 evidence) to see the source.',
    'Click 🔊 Read aloud under an answer to hear it.'],
  debate: ['Open the “Debate Room” tab.', 'Each column is one agent’s findings, with a confidence bar.', 'Under a finding you see the objection (red), the agent’s reply, and your decision.',
    'Click ◈ Remember to save a finding to your Memory Vault.'],
  evidence: ['Open the “Evidence” tab (or step 2 of New Analysis).', 'Drag files onto the dashed box, or click it to browse: images, screenshots, charts, PDF, Word, text, CSV.',
    'Check the extracted text. If something is wrong, fix it and click “Save correction”.', 'Click Preview to view a file. Click Reject to leave it out of the analysis (Restore brings it back).'],
  whatif: ['Open the “What-If” tab of a finished analysis.', 'Edit any assumption, add one with “＋ Add assumption”, or remove one with ✕.', 'Give the scenario a name and click ▶ Run scenario. It runs as a new version.',
    'When it finishes, the comparison shows what changed and why. Use the version selector at the top to switch versions.'],
  board: ['Open the “Thinking Board” tab.', 'Choose a view: Evidence map, Mind map, Decision tree or Agent workflow.', 'Click any box to read its explanation and its connections on the right.',
    'Click ⇩ PNG or ⇩ SVG to save the diagram as an image.'],
  trace: ['Open the “Decision Trace” tab.', 'Scroll the timeline to see every step, grouped by version.', 'Use the filter buttons (agents, findings, conflicts, evidence, human, memory).',
    'On the right, compare any two versions.'],
  new: ['Click ✚ New Analysis in the sidebar (or press N).', 'Click a scenario button to fill the form, or type your problem, or click 🎤 and speak it.',
    'Choose Debate intensity: Gentle, Balanced or Ruthless.', 'Tick the checkpoints where you want to approve things. Tick “depends on a location” only if a place matters.',
    'Click Continue to evidence, add files if you have any, then click ▶ Start analysis.'],
  memory: ['Click Memory Vault in the sidebar.', 'Finished analyses store their recommendation and strong findings here automatically.', 'Click Edit to change a memory, Delete to remove it, or ＋ Add memory to write your own note.',
    'New tasks show which memories they reused on their Overview tab.'],
  conflicts: ['Click Conflict Center in the sidebar.', 'Use the tabs to filter by status (unresolved, resolved, upheld, rejected).', 'Click “Decide now” or “Open debate” on a card to jump to it.'],
  insights: ['Click Insights in the sidebar.', 'See totals, findings by agent, what happened to findings, confidence over time and how conflicts were resolved.', 'Hover over bars and points for exact numbers.'],
  power: ['Press Ctrl + K anywhere to open the command palette. Type a page, task or action and press Enter.', 'Press ? to see all keyboard shortcuts (N new, / search, 1–9 tabs, R read aloud).'],
  services: ['Click Services in the sidebar to see what is connected.', 'To turn on Claude AI: close the app, double-click start-with-claude.cmd in C:\\AgentForge, paste your Anthropic API key, press Enter.',
    'Optional: install Tesseract OCR to read text in images without AI.'],
};

export function helpView() {
  return h('div.stack',
    h('div.card.help-hero',
      h('div', h('h4', 'Help & tutorial'), h('h1', 'Learn AgentForge in 8 minutes'),
        h('p.muted', 'Press play and watch: a narrated tutorial drives the real app, moves the cursor, clicks buttons and explains every feature. Pause with Space, skip with the arrow keys, exit with Esc.'),
        h('div.row', h('button.btn.primary', { style: { padding: '14px 22px', fontSize: '15px' }, onclick: () => playTutorial() }, '▶ Play the full tutorial'),
          h('span.small.faint', `${CHAPTERS.length} chapters · voice narration · creates one demo analysis`))),
      h('div.help-play', { onclick: () => playTutorial(), role: 'button', tabindex: 0, 'aria-label': 'Play tutorial' }, h('span', '▶'))),
    h('h2', { style: { marginTop: '8px' } }, 'Chapters — watch one, or read the steps'),
    h('div.help-grid', CHAPTERS.map((c, i) => h('div.card.help-ch',
      h('div.row.between', h('div.row', h('span.help-num', i + 1), h('b', `${c.icon} ${c.title}`)),
        h('button.btn.small.primary', { onclick: () => playTutorial(c.id, false) }, '▶ Watch')),
      h('ol.small', (GUIDE[c.id] || []).map((s) => h('li', s)))))));
}
