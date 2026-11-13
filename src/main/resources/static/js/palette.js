import { api, h, mount, modal, closeModal, statusBadge } from './core.js';
import { getTemplates, openDemoLauncher } from './views/templates.js';

/**
 * Ctrl/⌘ + K command palette: jump to any page or task, run actions, launch demo scenarios.
 * Fuzzy-matches as you type; arrow keys + Enter to run.
 */
export function initPalette({ isSignedIn, actions }) {
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      if (!isSignedIn()) return;
      e.preventDefault();
      open();
    }
  }, true);

  async function open() {
    const input = h('input.palette-input', { placeholder: 'Type a command, page or task…', 'aria-label': 'Command palette' });
    const list = h('div.palette-list', { role: 'listbox' });
    let items = baseItems();
    let shown = [], active = 0;

    const render = () => {
      const q = input.value.trim().toLowerCase();
      shown = (q ? items.map((it) => [it, score(it.label + ' ' + (it.hint || '') + ' ' + it.group, q)]).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1]).map(([it]) => it) : items).slice(0, 40);
      active = Math.min(active, Math.max(0, shown.length - 1));
      let lastGroup = null;
      const rows = [];
      shown.forEach((it, i) => {
        if (it.group !== lastGroup) { rows.push(h('div.palette-group', it.group)); lastGroup = it.group; }
        rows.push(h('div.palette-item', { class: i === active ? 'active' : '', role: 'option', onmouseenter: () => { active = i; paint(); }, onclick: () => run(it) },
          h('span.p-ico', it.icon), h('span.grow', it.label, it.hint ? h('span.faint.small', '  ', it.hint) : null), it.right || null));
      });
      mount(list, rows.length ? rows : h('div.state.small', 'No matches'));
    };
    const paint = () => list.querySelectorAll('.palette-item').forEach((el, i) => el.classList.toggle('active', i === active));
    const run = (it) => { closeModal(); it.run(); };

    input.addEventListener('input', () => { active = 0; render(); });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown') { e.preventDefault(); active = Math.min(shown.length - 1, active + 1); paint(); scroll(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); active = Math.max(0, active - 1); paint(); scroll(); }
      else if (e.key === 'Enter') { e.preventDefault(); if (shown[active]) run(shown[active]); }
    });
    const scroll = () => list.querySelectorAll('.palette-item')[active]?.scrollIntoView({ block: 'nearest' });

    modal(h('div.palette', h('div.palette-head', h('span', '⌘'), input, h('kbd', 'Esc')), list,
      h('div.palette-foot.small.faint', h('kbd', '↑'), h('kbd', '↓'), ' navigate · ', h('kbd', 'Enter'), ' run · ', h('kbd', 'Ctrl K'), ' open anywhere')));
    document.getElementById('modalCard').classList.add('palette-card');
    const observer = new MutationObserver(() => { if (document.getElementById('modal').classList.contains('hidden')) { document.getElementById('modalCard').classList.remove('palette-card'); observer.disconnect(); } });
    observer.observe(document.getElementById('modal'), { attributes: true });
    render(); input.focus();

    // Load tasks and templates asynchronously.
    const [tasks, templates] = await Promise.all([api('/tasks').catch(() => []), getTemplates().catch(() => [])]);
    items = [...baseItems(),
      ...tasks.slice(0, 25).map((t) => ({ group: 'Analyses', icon: '◇', label: t.title, right: statusBadge(t.status), run: () => (location.hash = `#/task/${t.id}`) })),
      ...tasks.slice(0, 8).filter((t) => t.status === 'completed').map((t) => ({ group: 'Ask the agents', icon: '💬', label: `Ask about: ${t.title}`, run: () => (location.hash = `#/task/${t.id}/ask`) })),
      ...templates.map((t) => ({ group: 'Launch a demo scenario', icon: t.icon, label: t.name, hint: t.blurb, run: () => launch(t) }))];
    render();
  }

  async function launch(t) {
    try {
      const r = await api(`/templates/${t.id}/launch`, { method: 'POST' });
      location.hash = `#/task/${r.taskId}/radar`;
    } catch { openDemoLauncher(); }
  }

  function baseItems() {
    const go = (hash) => () => (location.hash = hash);
    return [
      { group: 'Navigate', icon: '◈', label: 'Command Center', run: go('#/dashboard') },
      { group: 'Navigate', icon: '✚', label: 'New Analysis', run: go('#/new') },
      { group: 'Navigate', icon: '⚔', label: 'Conflict Center', run: go('#/conflicts') },
      { group: 'Navigate', icon: '⬡', label: 'Memory Vault', run: go('#/memory') },
      { group: 'Navigate', icon: '▤', label: 'Insights', run: go('#/insights') },
      { group: 'Navigate', icon: '◎', label: 'Services', run: go('#/status') },
      { group: 'Actions', icon: '🎬', label: 'Play the tutorial', hint: 'narrated walkthrough of every feature', run: () => import('./tour/index.js').then((m) => m.playTutorial()) },
      { group: 'Navigate', icon: '?', label: 'Help & Tutorial', run: go('#/help') },
      { group: 'Actions', icon: '⚡', label: 'Launch a live demo…', run: openDemoLauncher },
      { group: 'Actions', icon: '✦', label: 'Toggle live wallpaper', run: actions.toggleWallpaper },
      { group: 'Actions', icon: '⌨', label: 'Keyboard shortcuts', run: actions.shortcuts },
      { group: 'Actions', icon: '⎋', label: 'Sign out', run: actions.logout },
    ];
  }
}

/** Simple fuzzy score: substring beats ordered subsequence; 0 = no match. */
function score(text, q) {
  const t = text.toLowerCase();
  const i = t.indexOf(q);
  if (i >= 0) return 100 - i;
  let j = 0;
  for (const c of t) if (c === q[j]) j++;
  return j === q.length ? 10 : 0;
}
