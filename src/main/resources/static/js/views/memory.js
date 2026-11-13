import { api, h, mount, toast, empty, fmtDate, confirmDialog, modal, closeModal } from '../core.js';

/** Agent Memory Vault: inspect, edit, add and delete what agents may reuse. Scoped to the signed-in user. */
export async function memoryView() {
  let items = await api('/memory');
  const box = h('div.stack');
  const q = h('input', { placeholder: 'Filter memories…', 'aria-label': 'Filter memories' });
  q.oninput = () => render();

  function editor(m) {
    const content = h('textarea', { value: m?.content || '', style: { minHeight: '140px' } });
    const tags = h('input', { value: m?.tags || '', placeholder: 'comma,separated,tags (used for retrieval)' });
    const save = h('button.btn.primary', {}, 'Save');
    save.onclick = async () => {
      try {
        if (m) await api(`/memory/${m.id}`, { method: 'PATCH', body: { content: content.value, tags: tags.value } });
        else await api('/memory', { method: 'POST', body: { content: content.value, tags: tags.value, kind: 'note' } });
        closeModal(); toast('Memory saved', 'ok'); items = await api('/memory'); render();
      } catch (err) { toast(err.message, 'bad'); }
    };
    modal(h('div.stack', h('h2', m ? `Edit memory #${m.id}` : 'Add a memory'),
      h('p.muted.small', 'Agents retrieve memories whose words overlap a new task, and every reuse is shown on that task.'),
      h('label', 'Content', content), h('label', 'Tags', tags),
      h('div.row', { style: { justifyContent: 'flex-end' } }, h('button.btn', { onclick: closeModal }, 'Cancel'), save)));
  }

  function render() {
    const f = q.value.toLowerCase();
    const list = items.filter((m) => !f || m.content.toLowerCase().includes(f) || (m.tags || '').toLowerCase().includes(f));
    mount(box, list.length ? list.map((m) => h('div.card.tight',
      h('div.row.between', h('div.row', h('span.badge', m.kind), h('span.small.faint', `#${m.id} · ${m.source || ''}`), m.use_count ? h('span.badge.primary', `reused ${m.use_count}×`) : null),
        h('div.row', h('button.btn.small', { onclick: () => editor(m) }, 'Edit'),
          h('button.btn.small.danger', { onclick: async () => {
            if (!(await confirmDialog('Delete this memory?', 'Agents will no longer be able to reuse it. Tasks that already used it keep a snapshot in their history. This cannot be undone.', 'Delete', true))) return;
            try { await api(`/memory/${m.id}`, { method: 'DELETE' }); items = items.filter((x) => x.id !== m.id); render(); toast('Memory deleted', 'ok'); } catch (err) { toast(err.message, 'bad'); }
          } }, 'Delete'))),
      h('p', { style: { margin: '8px 0 4px', whiteSpace: 'pre-wrap' } }, m.content),
      h('div.small.faint', m.task_id ? h('a', { href: `#/task/${m.task_id}` }, m.task_title || `task #${m.task_id}`) : 'manual note', ' · ', fmtDate(m.updated_at), m.tags ? ` · tags: ${m.tags}` : '')))
      : empty('◈', items.length ? 'No matches' : 'Your memory vault is empty', items.length ? 'Try another filter.' : 'Completed analyses store their recommendation and well-supported findings here automatically. You can also save findings from the Debate Room or add notes.'));
  }
  render();
  return h('div',
    h('div.page-head', h('div', h('h1', 'Agent Memory Vault'), h('p.muted', 'Context and approved findings agents can reuse in future tasks. Only you can see your vault.')),
      h('button.btn.primary', { onclick: () => editor(null) }, '＋ Add memory')),
    h('div.card.tight', { style: { marginBottom: '14px' } }, q), box);
}
