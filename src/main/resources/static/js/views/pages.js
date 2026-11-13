import { api, h, svg, mount, empty, fmtDate, statusBadge, engineBadge, agentChip, pct, loading, errorState, AGENTS } from '../core.js';
import { onLeave, loadStatus } from '../app.js';
import { openDemoLauncher } from './templates.js';

// ------------------------------------------------------------- dashboard
export async function dashboardView() {
  const [stats, tasks] = await Promise.all([api('/stats'), api('/tasks')]);
  const listBox = h('div');
  const WAVES = ['M0,40 C20,30 30,10 50,18 S80,40 100,8', 'M0,30 C15,38 35,6 55,20 S85,30 100,12', 'M0,36 C25,12 40,34 60,16 S90,22 100,4', 'M0,24 C20,40 45,8 65,26 S88,10 100,18'];
  const COLORS = ['#818cf8', '#34d399', '#fb7185', '#c084fc'];
  let si = 0;
  const stat = (n, label, href) => {
    const i = si++;
    return h('a.card.tight.stat', { href, style: { textDecoration: 'none', color: 'inherit' } }, h('b', n), h('span', label),
      svg('svg', { class: 'spark', viewBox: '0 0 100 44', preserveAspectRatio: 'none' },
        svg('path', { d: WAVES[i] + ' L100,44 L0,44 Z', fill: COLORS[i], opacity: 0.12 }),
        svg('path', { d: WAVES[i], fill: 'none', stroke: COLORS[i], 'stroke-width': 1.5, 'vector-effect': 'non-scaling-stroke' })));
  };
  const roster = h('div.row', { style: { gap: '8px' } }, Object.entries(AGENTS).filter(([id]) => id !== 'user').map(([id, a]) =>
    h('span.badge', { style: { color: a.hex, borderColor: a.hex + '66', background: a.hex + '14', boxShadow: `0 0 16px -6px ${a.hex}` } }, h('span.dot'), a.label)));

  const renderList = (items) => mount(listBox, items.length ? h('div.list', items.map((t) => h('a.list-item.clickable', { href: `#/task/${t.id}`, style: { textDecoration: 'none', padding: '12px 10px', borderRadius: '8px' } },
    h('div.grow', h('div.title', t.title), h('div.small.muted', 'Updated ', fmtDate(t.updated_at), t.current_version > 1 ? ` · ${t.current_version} versions` : '', t.input_mode === 'voice' ? ' · 🎤 voice' : '')),
    t.unresolved > 0 ? h('span.badge.bad', `⚔ ${t.unresolved} unresolved`) : null,
    t.confidence != null ? h('span.small.muted', 'confidence ', pct(t.confidence)) : null,
    engineBadge(t.engine), statusBadge(t.status))))
    : empty('◇', 'No analyses yet', 'Describe a problem by voice or text, add evidence, and let six agents debate it.',
      h('a.btn.primary', { href: '#/new' }, '＋ Start your first analysis')));
  renderList(tasks);

  const onStatus = async () => { renderList(await api('/tasks').catch(() => tasks)); };
  window.addEventListener('af:task-status', onStatus);
  onLeave(() => window.removeEventListener('af:task-status', onStatus));

  return h('div',
    h('div.page-head', h('div', h('h4', `Welcome back · ${new Date().toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}`),
      h('h1', 'Command Center'), h('p.muted', 'Your agent team, live analyses, conflicts and memory at a glance.')),
      h('div.row', h('button.btn', { onclick: openDemoLauncher, style: { padding: '13px 18px' } }, '⚡ Live demo'),
        h('a.btn.primary', { href: '#/new', style: { padding: '13px 20px' } }, '✚ New Analysis'))),
    h('div.card.tight.row.between', { style: { marginBottom: '18px' } },
      h('div.row', h('span.badge.ok', h('span.dot'), 'All agents online'), roster),
      h('span.small.faint.mono', 'JAVA ORCHESTRATOR · SSE LIVE')),
    h('div.grid.g4', { style: { marginBottom: '16px' } },
      stat(stats.tasks, 'Analyses', '#/dashboard'), stat(stats.completed, 'Completed', '#/dashboard'),
      stat(stats.unresolved, 'Unresolved conflicts', '#/conflicts'), stat(stats.memories, 'Memories stored', '#/memory')),
    stats.awaiting > 0 ? h('div.card.gate', { style: { marginBottom: '16px' } }, h('b', `⏸ ${stats.awaiting} analysis(es) waiting for your approval.`), ' Open them below to review and resume.') : null,
    h('div.card', h('div.card-head', h('h2', 'Recent analyses')), listBox));
}

// ------------------------------------------------------------- conflict center
export async function conflictsView() {
  const conflicts = await api('/conflicts');
  const filters = ['all', 'unresolved', 'open', 'resolved', 'user_upheld', 'user_rejected'];
  let active = conflicts.some((c) => c.status === 'unresolved') ? 'unresolved' : 'all';
  const box = h('div.stack');
  const bar = h('div.tabs');
  const render = () => {
    mount(bar, filters.map((f) => h('button.tab', { class: f === active ? 'active' : '', onclick: () => { active = f; render(); } },
      f.replace('_', ' '), h('span.badge', conflicts.filter((c) => f === 'all' || c.status === f).length))));
    const items = conflicts.filter((c) => active === 'all' || c.status === active);
    mount(box, items.length ? items.map((c) => h('div.card.tight',
      h('div.row.between', h('div.row', agentChip('critic'), h('span.muted', '→'), agentChip(c.target_agent),
        h('span.badge', { class: c.severity === 'high' ? 'bad' : c.severity === 'medium' ? 'warn' : '' }, c.severity)),
      h('span.badge', { class: c.status === 'unresolved' ? 'bad' : c.status.startsWith('user') ? 'primary' : c.status === 'resolved' ? 'ok' : '' }, c.status.replace('_', ' '))),
      h('div', { style: { margin: '8px 0 4px' } }, h('b', 'On: '), c.finding_title || `finding #${c.finding_id}`),
      h('div.muted', c.objection),
      c.resolution ? h('div.small', { style: { marginTop: '6px' } }, h('b', 'Response: '), c.resolution) : null,
      h('div.row.between', { style: { marginTop: '8px' } }, h('span.small.faint', c.task_title, ' · v', c.version_no),
        h('a.btn.small', { href: `#/task/${c.task_id}/${c.status === 'unresolved' && c.task_status === 'awaiting_approval' ? 'overview' : 'debate'}` },
          c.status === 'unresolved' && c.task_status === 'awaiting_approval' ? 'Decide now →' : 'Open debate →'))))
      : empty('⚔', 'No conflicts here', active === 'all' ? 'Conflicts appear when the Devil\'s Advocate challenges a finding.' : 'Nothing matches this filter.'));
  };
  render();
  return h('div', h('div.page-head', h('div', h('h1', 'Conflict Center'),
    h('p.muted', 'Every objection raised by the Devil\'s Advocate across your latest analysis versions, and how it was resolved.'))), bar, box);
}

// ------------------------------------------------------------- services status
export async function statusView() {
  const s = await loadStatus();
  if (!s) throw new Error('Could not load service status');
  const speechOk = !!(window.SpeechRecognition || window.webkitSpeechRecognition);
  const row = (name, ok, msg, how) => h('div.list-item', h('span.badge', { class: ok ? 'ok' : 'warn' }, ok ? 'Available' : 'Unavailable'),
    h('div.grow', h('div.title', name), h('div.small.muted', msg), !ok && how ? h('div.small', { style: { marginTop: '4px' } }, how) : null));
  return h('div',
    h('div.page-head', h('div', h('h1', 'Services'), h('p.muted', 'What is connected right now. Unavailable features say so instead of pretending to work.'))),
    h('div.card', h('div.list',
      row('AI reasoning & vision (Claude)', s.ai.available, s.ai.available ? `Model ${s.ai.model}` : s.ai.message,
        h('span', 'Set the ', h('code', 'ANTHROPIC_API_KEY'), ' environment variable and restart the server.')),
      row('OCR (Tesseract)', s.ocr.available, s.ocr.message, 'Install Tesseract OCR and make sure the tesseract command is on PATH (or set TESSERACT_CMD).'),
      row('PDF text extraction', s.pdf.available, s.pdf.message),
      row('Geocoding & maps', s.geo.available, s.geo.message),
      row('Speech recognition (browser)', speechOk, speechOk ? 'Web Speech API available in this browser' : 'This browser has no speech recognition. Use typed input, or try Chrome or Edge.'),
      row('Read aloud (browser)', 'speechSynthesis' in window, 'Speech synthesis for reading reports aloud'),
      row('Backend', true, s.backend))));
}

// ------------------------------------------------------------- search
export async function searchView(q) {
  document.getElementById('searchInput').value = q;
  const r = await api('/search?q=' + encodeURIComponent(q));
  const total = r.tasks.length + r.findings.length + r.memories.length + r.evidence.length;
  const mark = (text) => {
    const s = String(text ?? ''); const i = s.toLowerCase().indexOf(q.toLowerCase());
    if (i < 0) return s.length > 200 ? s.slice(0, 200) + '…' : s;
    const start = Math.max(0, i - 60);
    return [start > 0 ? '…' : '', s.slice(start, i), h('mark', s.slice(i, i + q.length)), s.slice(i + q.length, i + q.length + 120), '…'];
  };
  const section = (title, items, render) => items.length ? h('div.card', h('h3', title, h('span.badge', items.length)), h('div.list', items.map(render))) : null;
  return h('div.stack',
    h('div.page-head', h('div', h('h1', `Search: “${q}”`), h('p.muted', `${total} result(s) across your workspace`))),
    total === 0 ? h('div.card', empty('⌕', 'No results', 'Try a different word.')) : null,
    section('Tasks', r.tasks, (t) => h('a.list-item', { href: `#/task/${t.id}` }, h('div.grow.title', mark(t.title)), statusBadge(t.status))),
    section('Findings', r.findings, (f) => h('a.list-item', { href: `#/task/${f.task_id}/debate` }, agentChip(f.agent), h('div.grow', h('div', mark(f.title)), h('div.small.faint', f.task_title)))),
    section('Memory', r.memories, (m) => h('a.list-item', { href: '#/memory' }, h('span.badge', m.kind), h('div.grow', mark(m.content)))),
    section('Evidence', r.evidence, (e) => h('a.list-item', { href: `#/task/${e.task_id}/evidence` }, '📎', h('div.grow', h('div', mark(e.filename)), h('div.small.faint', e.task_title)))));
}

export { loading, errorState };
