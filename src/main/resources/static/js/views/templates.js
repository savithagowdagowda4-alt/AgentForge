import { api, h, modal, closeModal, toast, loading, mount } from '../core.js';

let cache = null;
export async function getTemplates() {
  if (!cache) cache = await api('/templates');
  return cache;
}

/** Modal: pick a scenario and launch it immediately — creates the task, attaches sample evidence, starts the agents. */
export async function openDemoLauncher() {
  const body = h('div.stack', loading('Loading scenarios…'));
  modal(h('div.stack', h('div.row.between', h('div', h('h4', 'Live demo'), h('h2', { style: { margin: 0 } }, '⚡ Launch a scenario')),
    h('button.btn.small', { onclick: closeModal }, 'Close')),
  h('p.muted', 'Each scenario creates a real analysis with sample evidence and starts all six agents right away. Watch them on the radar.'), body), { wide: true });
  try {
    const list = await getTemplates();
    mount(body, h('div.tpl-grid', list.map((t) => templateCard(t, async (btn) => {
      btn.disabled = true; btn.textContent = 'Launching…';
      try {
        const r = await api(`/templates/${t.id}/launch`, { method: 'POST' });
        closeModal(); toast(`Launched “${t.name}” — agents are working`, 'ok');
        window.dispatchEvent(new CustomEvent('af:pulse', { detail: { color: '#a78bfa' } }));
        location.hash = `#/task/${r.taskId}/radar`;
      } catch (err) { toast(err.message, 'bad'); btn.disabled = false; btn.textContent = '▶ Launch'; }
    }, '▶ Launch'))));
  } catch (err) { mount(body, h('div.error-text', err.message)); }
}

export function templateCard(t, onAction, actionLabel) {
  const btn = h('button.btn.primary.small', {}, actionLabel);
  btn.onclick = (e) => { e.stopPropagation(); onAction(btn); };
  return h('div.tpl', { onclick: () => btn.click(), tabindex: 0, role: 'button', onkeydown: (e) => { if (e.key === 'Enter') btn.click(); } },
    h('div.tpl-ico', t.icon),
    h('div.stack', { style: { gap: '4px' } },
      h('div.row.between', h('b', t.name), h('span.badge', t.category)),
      h('div.small.muted', t.blurb),
      h('div.row.small.faint', `⚔ ${t.intensity}`, t.evidence.length ? ` · 📎 ${t.evidence.length} evidence` : '', t.location ? ' · 📍 location' : '')),
    h('div', { style: { alignSelf: 'center' } }, btn));
}
