import { api, h, mount, toast, closeModal, loading, errorState, fmtDate, stream, speech, modal } from './core.js';
import { dashboardView, conflictsView, statusView, searchView } from './views/pages.js';
import { newTaskView } from './views/newtask.js';
import { taskView } from './views/task.js';
import { memoryView } from './views/memory.js';
import { startWallpaper } from './wallpaper.js';
import { insightsView } from './views/insights.js';
import { initPalette } from './palette.js';
import { helpView } from './views/help.js';
import { playTutorial } from './tour/index.js';

const state = { user: null, status: null, closeUserStream: null, cleanup: [] };
export const app = state;

const $ = (id) => document.getElementById(id);
const seenNotifications = new Set();

// ------------------------------------------------------------- live wallpaper & ambient effects
const wallpaper = startWallpaper($('wallpaper'));
const syncMotionBtn = () => $('motionBtn').classList.toggle('on', wallpaper.enabled);
$('motionBtn').onclick = () => { wallpaper.setEnabled(!wallpaper.enabled); syncMotionBtn(); toast(wallpaper.enabled ? 'Live wallpaper on' : 'Live wallpaper paused', 'info', 1800); };
syncMotionBtn();
$('tutorialBtn').onclick = () => playTutorial();
initPalette({
  isSignedIn: () => !!state.user,
  actions: {
    toggleWallpaper: () => $('motionBtn').click(),
    shortcuts: () => showShortcuts(),
    logout: () => $('logoutBtn').click(),
  },
});

// Spotlight + neon edge follow the pointer on glass cards.
document.addEventListener('pointermove', (e) => {
  const card = e.target.closest?.('.card');
  if (!card) return;
  const r = card.getBoundingClientRect();
  card.style.setProperty('--mx', `${e.clientX - r.left}px`);
  card.style.setProperty('--my', `${e.clientY - r.top}px`);
}, { passive: true });

// Count-up animation for big numbers whenever a view renders them.
new MutationObserver(() => {
  document.querySelectorAll('.stat b:not([data-counted])').forEach((b) => {
    b.dataset.counted = '1';
    const target = Number(b.textContent);
    if (!Number.isFinite(target) || target === 0) return;
    const t0 = performance.now();
    const tick = (t) => { const k = Math.min(1, (t - t0) / 900); b.textContent = Math.round(target * (1 - Math.pow(1 - k, 3))); if (k < 1) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  });
}).observe(document.getElementById('view'), { childList: true, subtree: true });

setInterval(() => { $('clock').textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }, 1000);

// Sign-in hero: orbiting agents and a typing ticker.
const ORB = [['◆', 'Coordinator', '#818cf8'], ['◎', 'Analyst', '#38bdf8'], ['◉', 'Inspector', '#2dd4bf'], ['✦', 'Synthesizer', '#c084fc'], ['▲', 'Risk', '#fbbf24'], ['✕', 'Advocate', '#fb7185']];
ORB.forEach(([icon, name, color], i) => {
  const a = (-90 + i * 60) * Math.PI / 180;
  $('orbOrbit').append(h('div.orb-agent', { style: { left: `${50 + 44 * Math.cos(a)}%`, top: `${50 + 44 * Math.sin(a)}%`, '--c': color } }, h('i', icon), h('b', name)));
});
const LINES = ['> coordinator: decomposing "Should we outsource QA?"', '> inspector: evidence shows $32,000 — description says $40,000', "> devil's advocate: objection raised · severity HIGH",
  '> risk: dependency risk on vendor contract (0.72)', '> you: upheld finding #6 · resuming', '> synthesizer: recommendation ready · confidence 81%'];
let li = 0;
(function typeLoop() {
  if ($('auth').classList.contains('hidden')) { setTimeout(typeLoop, 1500); return; }
  const line = LINES[li++ % LINES.length]; let i = 0;
  const agents = document.querySelectorAll('.orb-agent');
  const hit = agents[li % agents.length]; hit?.classList.remove('live'); void hit?.offsetWidth; hit?.classList.add('live');
  window.dispatchEvent(new CustomEvent('af:pulse', { detail: { color: ORB[li % ORB.length][2] } }));
  const step = () => { $('typer').textContent = line.slice(0, ++i); if (i < line.length) setTimeout(step, 24); else setTimeout(typeLoop, 1600); };
  step();
})();

// ------------------------------------------------------------- auth
let authMode = 'login';
$('authTabs').addEventListener('click', (e) => {
  const b = e.target.closest('[data-mode]'); if (!b) return;
  authMode = b.dataset.mode;
  document.querySelectorAll('#authTabs .tab').forEach((t) => t.classList.toggle('active', t === b));
  document.querySelectorAll('.reg-only').forEach((el) => el.classList.toggle('hidden', authMode !== 'register'));
  $('authSubmit').textContent = authMode === 'login' ? 'Sign in →' : 'Create account →';
  $('authForm').password.autocomplete = authMode === 'login' ? 'current-password' : 'new-password';
});
$('authForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target; $('authError').textContent = '';
  $('authSubmit').disabled = true;
  try {
    const body = { username: f.username.value, password: f.password.value, displayName: f.displayName.value };
    state.user = await api('/auth/' + authMode, { method: 'POST', body });
    f.password.value = '';
    enterApp();
  } catch (err) { $('authError').textContent = err.message; }
  finally { $('authSubmit').disabled = false; }
});
$('logoutBtn').onclick = async () => { await api('/auth/logout', { method: 'POST' }).catch(() => {}); showAuth(); };
window.addEventListener('af:unauthorized', () => { if (state.user) { toast('Your session ended. Please sign in again.', 'warn'); showAuth(); } });

function showAuth() {
  state.user = null;
  state.closeUserStream?.();
  $('app').classList.add('hidden'); $('auth').classList.remove('hidden');
  $('authForm').username.focus();
}

async function enterApp() {
  $('auth').classList.add('hidden'); $('app').classList.remove('hidden');
  $('userBtn').textContent = '👤 ' + (state.user.display_name || state.user.username);
  loadStatus();
  refreshNotifications();
  state.closeUserStream?.();
  state.closeUserStream = stream('/stream', {
    notification: (n) => {
      if (seenNotifications.has(n.id)) return;
      seenNotifications.add(n.id);
      wallpaper.pulse(n.kind === 'failed' ? '#fb7185' : n.kind === 'completed' ? '#34d399' : '#fbbf24');
      toast(h('div', h('b', n.kind === 'conflict' ? '⚔ ' : n.kind === 'completed' ? '✓ ' : '🔔 '), n.message,
        n.task_id ? h('div', h('a', { href: `#/task/${n.task_id}`, onclick: closeModal }, 'Open task →')) : null),
        n.kind === 'failed' ? 'bad' : n.kind === 'completed' ? 'ok' : 'warn', 7000);
      refreshNotifications();
    },
    'task-status': (s) => window.dispatchEvent(new CustomEvent('af:task-status', { detail: s })),
  });
  route();
  offerTutorial();
}

export async function loadStatus() {
  try {
    state.status = await api('/status');
    const ai = state.status.ai.available;
    const b = $('engineBadge');
    b.className = 'badge ' + (ai ? 'primary' : 'warn');
    b.textContent = ai ? '✦ Claude connected' : '⚙ Rule-based mode';
    b.title = state.status.ai.message;
    $('sysEngine').textContent = ai ? 'CLAUDE · ONLINE' : 'RULES · ONLINE';
  } catch { /* status is optional */ }
  return state.status;
}

// ------------------------------------------------------------- notifications
async function refreshNotifications() {
  try {
    const list = await api('/notifications');
    const unread = list.filter((n) => !n.is_read).length;
    $('bellCount').textContent = unread; $('bellCount').classList.toggle('hidden', unread === 0);
    const panel = $('notifPanel');
    mount(panel,
      h('div.row.between', { style: { padding: '10px 14px', borderBottom: '1px solid var(--line)' } }, h('b', 'Notifications'),
        h('button.btn.ghost.small', { onclick: async () => { await api('/notifications/read', { method: 'POST' }); refreshNotifications(); } }, 'Mark all read')),
      list.length ? list.map((n) => h('div.notif-item', {
        class: n.is_read ? '' : 'unread',
        onclick: async () => { await api(`/notifications/${n.id}/read`, { method: 'POST' }); panel.classList.add('hidden'); if (n.task_id) location.hash = `#/task/${n.task_id}`; refreshNotifications(); },
      }, h('span', n.kind === 'conflict' ? '⚔' : n.kind === 'completed' ? '✓' : n.kind === 'failed' ? '⚠' : '🔔'),
      h('div', h('div', n.message), h('div.small.faint', fmtDate(n.created_at))))) : h('div.state.small', 'No notifications yet'));
  } catch { /* ignore */ }
}
$('bellBtn').onclick = (e) => { e.stopPropagation(); $('notifPanel').classList.toggle('hidden'); $('userMenu').classList.add('hidden'); };
$('userBtn').onclick = (e) => { e.stopPropagation(); $('userMenu').classList.toggle('hidden'); $('notifPanel').classList.add('hidden'); };
document.addEventListener('click', (e) => {
  if (!e.target.closest('.notif')) $('notifPanel').classList.add('hidden');
  if (!e.target.closest('.user')) $('userMenu').classList.add('hidden');
  if (!e.target.closest('#sidebar') && !e.target.closest('#menuBtn')) $('sidebar').classList.remove('open');
});
$('menuBtn').onclick = () => $('sidebar').classList.toggle('open');

// ------------------------------------------------------------- search
$('searchForm').addEventListener('submit', (e) => {
  e.preventDefault();
  const q = $('searchInput').value.trim();
  if (q) location.hash = '#/search?q=' + encodeURIComponent(q);
});

// ------------------------------------------------------------- shortcuts
const SHORTCUTS = [
  ['Ctrl + K', 'Command palette — jump anywhere, run actions, launch demos'], ['/', 'Focus global search'], ['n', 'New analysis'], ['g then d', 'Go to dashboard'], ['g then c', 'Go to Conflict Center'],
  ['g then m', 'Go to Memory Vault'], ['g then i', 'Go to Insights'], ['1 – 9', 'Switch tab on a task page'], ['r', 'Read recommendation aloud / stop'],
  ['Ctrl + Enter', 'Submit the New Analysis form'], ['?', 'Show this help'], ['Esc', 'Close dialogs and menus'],
];
function showShortcuts() {
  modal(h('div.stack', h('h2', '⌨ Keyboard shortcuts'),
    h('div.kv', SHORTCUTS.map(([k, d]) => [h('dt', h('kbd', k)), h('dd', d)])),
    h('div.row', { style: { justifyContent: 'flex-end' } }, h('button.btn', { onclick: closeModal }, 'Close'))));
}
$('shortcutsBtn').onclick = showShortcuts;
let gPending = false;
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeModal(); $('notifPanel').classList.add('hidden'); $('userMenu').classList.add('hidden'); return; }
  if (!state.user) return;
  const typing = e.target.closest('input, textarea, select, [contenteditable]');
  if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
  if (gPending) {
    gPending = false;
    const dest = { d: '#/dashboard', c: '#/conflicts', m: '#/memory', s: '#/status', i: '#/insights' }[e.key];
    if (dest) { e.preventDefault(); location.hash = dest; }
    return;
  }
  if (e.key === '/') { e.preventDefault(); $('searchInput').focus(); }
  else if (e.key === 'n') { e.preventDefault(); location.hash = '#/new'; }
  else if (e.key === 'g') { gPending = true; setTimeout(() => (gPending = false), 1200); }
  else if (e.key === '?') { e.preventDefault(); showShortcuts(); }
  else window.dispatchEvent(new CustomEvent('af:key', { detail: e }));
});

// ------------------------------------------------------------- router
const routes = [
  [/^#\/dashboard$/, () => dashboardView()],
  [/^#\/new$/, () => newTaskView()],
  [/^#\/task\/(\d+)(?:\/(\w+))?$/, (m) => taskView(Number(m[1]), m[2])],
  [/^#\/memory$/, () => memoryView()],
  [/^#\/insights$/, () => insightsView()],
  [/^#\/help$/, () => helpView()],
  [/^#\/conflicts$/, () => conflictsView()],
  [/^#\/status$/, () => statusView()],
  [/^#\/search\?q=(.*)$/, (m) => searchView(decodeURIComponent(m[1]))],
];

export function onLeave(fn) { state.cleanup.push(fn); }

async function route() {
  if (!state.user) return;
  state.cleanup.splice(0).forEach((fn) => { try { fn(); } catch { /* ignore */ } });
  speech.stop();
  const hash = location.hash || '#/dashboard';
  const view = $('view');
  document.querySelectorAll('.nav a').forEach((a) => a.classList.toggle('active', hash.startsWith('#/' + a.dataset.nav)));
  for (const [re, fn] of routes) {
    const m = hash.match(re);
    if (m) {
      mount(view, loading());
      try { const node = await fn(m); if (node) mount(view, node); }
      catch (err) { mount(view, errorState(err, route)); }
      view.focus({ preventScroll: true });
      window.scrollTo(0, 0);
      return;
    }
  }
  location.hash = '#/dashboard';
}
window.addEventListener('hashchange', route);

// ------------------------------------------------------------- boot
(async () => {
  try { state.user = await api('/auth/me'); enterApp(); }
  catch { showAuth(); }
})();

/** First sign-in: offer the narrated tutorial once. */
function offerTutorial() {
  let seen = false;
  try { seen = localStorage.getItem('af-tour-seen') === '1'; } catch { /* ignore */ }
  if (seen) return;
  try { localStorage.setItem('af-tour-seen', '1'); } catch { /* ignore */ }
  setTimeout(() => modal(h('div.stack', { style: { textAlign: 'center' } },
    h('div', { style: { fontSize: '44px' } }, '🎬'), h('h2', 'New here? Watch the tutorial'),
    h('p.muted', 'A narrated walkthrough plays inside the app like a video and shows every feature in about 8 minutes. You can pause, skip or exit at any time.'),
    h('div.row', { style: { justifyContent: 'center' } },
      h('button.btn', { onclick: closeModal }, 'Maybe later'),
      h('button.btn.primary', { onclick: () => { closeModal(); playTutorial(); } }, '▶ Play tutorial')))), 900);
}
