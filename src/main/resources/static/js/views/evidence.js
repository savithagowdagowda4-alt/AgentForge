import { api, h, mount, toast, modal, empty, closeModal } from '../core.js';

const isImage = (t) => (t || '').startsWith('image/');
const isPdf = (t) => t === 'application/pdf';
const fileIcon = (t) => isImage(t) ? '🖼' : isPdf(t) ? '📄' : '📝';
const kb = (n) => n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB';

export function previewEvidence(ev) {
  const url = `/api/evidence/${ev.id}/file`;
  const body = isImage(ev.content_type) ? h('img.preview-img', { src: url, alt: ev.filename })
    : isPdf(ev.content_type) ? h('iframe.preview-frame', { src: url, title: ev.filename })
      : h('pre.text', { style: { maxHeight: '70vh' } }, ev.corrected_text || ev.extracted_text || '(no text)');
  modal(h('div.stack', h('div.row.between', h('h2', ev.filename), h('div.row', h('a.btn.small', { href: url, target: '_blank', rel: 'noopener' }, 'Open in new tab'),
    h('button.btn.small', { onclick: closeModal }, 'Close'))), body), { wide: true });
}

/**
 * Upload + review panel for a task's evidence. Shows what was extracted (OCR / PDF / vision),
 * lets the user correct it, and lets them reject or restore evidence.
 */
export function evidencePanel(taskId, { canUpload = () => true, uploadBlockedReason = '' } = {}) {
  const listBox = h('div.stack');
  const input = h('input', { type: 'file', multiple: true, accept: 'image/*,.pdf,.txt,.md,.csv,.json,.docx', style: { display: 'none' } });
  const zone = h('div.dropzone', { tabindex: 0, role: 'button' },
    h('div', { style: { fontSize: '26px' } }, '⇪'),
    h('b', 'Drop images, screenshots, charts, PDFs or documents here'),
    h('div.small', 'or click to browse · PNG, JPG, WEBP, GIF, PDF, DOCX, TXT, CSV, MD · max 15 MB each'));
  const progress = h('div.small.muted');

  async function upload(files) {
    if (!canUpload()) { toast(uploadBlockedReason || 'Uploads are not possible right now', 'warn'); return; }
    for (const f of files) {
      progress.textContent = `Uploading and extracting ${f.name}…`;
      const fd = new FormData(); fd.append('file', f);
      try {
        const ev = await api(`/tasks/${taskId}/evidence`, { method: 'POST', body: fd });
        toast(`${f.name}: ${ev.extraction_method === 'unavailable' || ev.extraction_method === 'unsupported' ? 'stored, but no text could be extracted' : 'extracted via ' + ev.extraction_method}`,
          ev.extraction_method === 'unavailable' || ev.extraction_method === 'failed' ? 'warn' : 'ok');
      } catch (err) { toast(`${f.name}: ${err.message}`, 'bad'); }
    }
    progress.textContent = '';
    refresh();
  }
  zone.onclick = () => input.click();
  zone.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } };
  input.onchange = () => { upload([...input.files]); input.value = ''; };
  zone.ondragover = (e) => { e.preventDefault(); zone.classList.add('over'); };
  zone.ondragleave = () => zone.classList.remove('over');
  zone.ondrop = (e) => { e.preventDefault(); zone.classList.remove('over'); upload([...e.dataTransfer.files]); };

  function card(ev) {
    const text = h('textarea', { value: ev.corrected_text ?? ev.extracted_text ?? '', 'aria-label': 'Extracted text' });
    const saveBtn = h('button.btn.small', { disabled: true }, 'Save correction');
    text.oninput = () => (saveBtn.disabled = false);
    saveBtn.onclick = async () => {
      saveBtn.disabled = true;
      try { await api(`/evidence/${ev.id}`, { method: 'PATCH', body: { text: text.value } }); toast('Correction saved; agents will use your text', 'ok'); refresh(); }
      catch (err) { toast(err.message, 'bad'); saveBtn.disabled = false; }
    };
    const rejected = ev.status === 'rejected';
    const toggle = h('button.btn.small', { class: rejected ? '' : 'danger' }, rejected ? 'Restore' : 'Reject');
    toggle.onclick = async () => {
      try { await api(`/evidence/${ev.id}/status`, { method: 'POST', body: { status: rejected ? 'active' : 'rejected' } }); refresh(); }
      catch (err) { toast(err.message, 'bad'); }
    };
    const method = ev.extraction_method;
    const methodBadge = h('span.badge', { class: ['unavailable', 'failed', 'unsupported'].includes(method) ? 'warn' : 'ok' }, method);
    return h('div.ev', { class: rejected ? 'rejected' : '' },
      h('div.ev-thumb', { onclick: () => previewEvidence(ev), title: 'Preview' },
        isImage(ev.content_type) ? h('img', { src: `/api/evidence/${ev.id}/file`, alt: ev.filename, loading: 'lazy' }) : fileIcon(ev.content_type)),
      h('div.stack', { style: { gap: '8px', minWidth: 0 } },
        h('div.row.between', h('div.row', h('b', ev.filename), h('span.small.faint', kb(ev.size_bytes || 0)), methodBadge, rejected ? h('span.badge.bad', 'rejected') : null),
          h('div.row', h('button.btn.small', { onclick: () => previewEvidence(ev) }, 'Preview'), toggle)),
        ev.extraction_note ? h('div.engine-note', ev.extraction_note) : null,
        ev.vision_summary ? h('div.small', h('b', 'Visual analysis: '), ev.vision_summary) : null,
        h('label', ev.corrected_text != null ? 'Extracted text (corrected by you)' : 'Extracted text — correct anything the extractor got wrong', text),
        h('div.row', saveBtn)));
  }

  async function refresh() {
    try {
      const list = await api(`/tasks/${taskId}/evidence`);
      mount(listBox, list.length ? list.map(card) : empty('📎', 'No evidence yet', 'Evidence is optional, but it lets the Evidence Inspector verify your description.'));
    } catch (err) { mount(listBox, h('div.error-text', err.message)); }
  }
  refresh();
  return { el: h('div.stack', zone, input, progress, listBox), refresh };
}
