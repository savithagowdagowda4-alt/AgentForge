// Tutorial player: plays a scripted, narrated walkthrough that drives the real app like a screen recording.
// A fake cursor moves and clicks, a spotlight highlights each feature, captions explain it and the
// browser's speech synthesis reads them aloud. Controls: pause/resume, previous, next, chapters, voice, exit.
import { h, mount } from '../core.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class TourPlayer {
  constructor(chapters) {
    this.chapters = chapters;          // [{ id, title, icon, steps: [...] }]
    this.steps = [];                   // flattened steps of the chapters being played
    this.i = 0;
    this.paused = false;
    this.stopped = true;
    this.voice = (() => { try { return localStorage.getItem('af-tour-voice') !== '0'; } catch { return true; } })();
    this.ctx = {};                     // shared state between steps (e.g. demo task id)
    this.token = 0;                    // invalidates in-flight steps when jumping
    this.build();
  }

  // ---------------------------------------------------------------- UI
  build() {
    this.spot = h('div.tour-spot.hidden');
    this.cursor = h('div.tour-cursor.hidden', h('div.tour-cursor-arrow'), h('div.tour-ripple'));
    this.caption = h('div.tour-caption.hidden');
    this.chapterLabel = h('span.tour-chapter');
    this.progress = h('div.tour-progress', h('i'));
    this.playBtn = h('button.tour-btn', { title: 'Pause / resume (Space)', onclick: () => this.togglePause() }, '⏸');
    this.voiceBtn = h('button.tour-btn', { title: 'Narration on/off', onclick: () => this.toggleVoice() }, this.voice ? '🔊' : '🔇');
    this.speed = 1;
    this.speedBtn = h('button.tour-btn.speed', { title: 'Playback speed', onclick: () => { this.speed = this.speed === 1 ? 1.5 : this.speed === 1.5 ? 2 : 1; this.speedBtn.textContent = this.speed + '×'; } }, '1×');
    this.chaptersMenu = h('div.tour-menu.hidden');
    this.bar = h('div.tour-bar.hidden',
      h('div.tour-rec', h('span.tour-dot'), 'TUTORIAL'), this.chapterLabel, this.progress,
      h('button.tour-btn', { title: 'Previous step (←)', onclick: () => this.jump(this.i - 1) }, '⏮'),
      this.playBtn,
      h('button.tour-btn', { title: 'Next step (→)', onclick: () => this.jump(this.i + 1) }, '⏭'),
      h('div', { style: { position: 'relative' } }, h('button.tour-btn', { title: 'Chapters', onclick: (e) => { e.stopPropagation(); this.toggleMenu(); } }, '☰'), this.chaptersMenu),
      this.speedBtn, this.voiceBtn,
      h('button.tour-btn', { title: 'Exit tutorial (Esc)', onclick: () => this.stop() }, '✕'));
    document.body.append(this.spot, this.cursor, this.caption, this.bar);
    document.addEventListener('keydown', (e) => {
      if (this.stopped) return;
      if (e.key === 'Escape') { e.stopPropagation(); this.stop(); }
      else if (e.key === ' ' && !e.target.closest('input, textarea')) { e.preventDefault(); this.togglePause(); }
      else if (e.key === 'ArrowRight' && !e.target.closest('input, textarea')) this.jump(this.i + 1);
      else if (e.key === 'ArrowLeft' && !e.target.closest('input, textarea')) this.jump(this.i - 1);
    }, true);
    window.addEventListener('resize', () => this.reposition());
    window.addEventListener('scroll', () => this.reposition(), true);
  }

  toggleMenu() {
    const open = this.chaptersMenu.classList.toggle('hidden');
    if (open) return;
    mount(this.chaptersMenu, this.chapters.map((c) => h('button.menu-item', {
      onclick: () => { this.chaptersMenu.classList.add('hidden'); this.play(this.chapters.indexOf(c), true); },
    }, `${c.icon}  ${c.title}`)));
  }

  toggleVoice() {
    this.voice = !this.voice;
    try { localStorage.setItem('af-tour-voice', this.voice ? '1' : '0'); } catch { /* ignore */ }
    this.voiceBtn.textContent = this.voice ? '🔊' : '🔇';
    if (!this.voice) speechSynthesis?.cancel();
  }

  togglePause() {
    this.paused = !this.paused;
    this.playBtn.textContent = this.paused ? '▶' : '⏸';
    this.bar.classList.toggle('paused', this.paused);
    if ('speechSynthesis' in window) { if (this.paused) speechSynthesis.pause(); else speechSynthesis.resume(); }
  }

  // ---------------------------------------------------------------- playback
  /** Play from a chapter. continueAfter=true plays the following chapters too. */
  async play(chapterIndex = 0, continueAfter = true) {
    const list = continueAfter ? this.chapters.slice(chapterIndex) : [this.chapters[chapterIndex]];
    this.steps = list.flatMap((c) => c.steps.map((s) => ({ ...s, chapter: c })));
    this.stopped = false; this.paused = false; this.playBtn.textContent = '⏸'; this.bar.classList.remove('paused');
    this.bar.classList.remove('hidden');
    document.body.classList.add('touring');
    this.run(0);
  }

  jump(i) {
    if (i < 0 || i >= this.steps.length) return;
    if (this.paused) this.togglePause();
    speechSynthesis?.cancel();
    this.run(i);
  }

  async run(start) {
    const token = ++this.token;
    for (this.i = start; this.i < this.steps.length; this.i++) {
      if (token !== this.token || this.stopped) return;
      const step = this.steps[this.i];
      this.chapterLabel.textContent = `${step.chapter.icon} ${step.chapter.title}`;
      this.progress.firstChild.style.width = `${Math.round((this.i + 1) / this.steps.length * 100)}%`;
      try { await this.playStep(step, token); }
      catch (err) { if (token === this.token) await this.showCaption(step, `⚠ ${err.message || err}`, token, 2500); }
    }
    if (token === this.token && !this.stopped) this.finish();
  }

  async playStep(step, token) {
    const live = () => token === this.token && !this.stopped;
    await this.waitWhilePaused(live);
    if (step.skip && await step.skip(this.api())) return;
    if (step.before) await step.before(this.api());
    const route = typeof step.route === 'function' ? step.route(this.ctx) : step.route;
    if (route && location.hash !== route) {
      this.caption.classList.add('hidden'); this.spot.classList.add('hidden'); this.current = null;
      location.hash = route; await sleep(900);
    }
    if (step.setup) await step.setup(this.api());
    if (!live()) return;
    const target = step.target ? await this.find(step.target, step.timeout ?? 9000) : null;
    if (!live()) return;
    if (target) {
      target.scrollIntoView({ block: 'center', behavior: 'smooth' });
      await sleep(350);
      this.highlight(target);
      await this.moveCursor(target);
    } else { this.highlight(null); this.cursor.classList.add('hidden'); }
    const speaking = this.showCaption(step, step.text, token, step.hold);
    if (step.action) { await sleep(600); await this.waitWhilePaused(live); if (live()) await step.action(this.api(), target); }
    await speaking;
    if (step.after && live()) await step.after(this.api());
  }

  async waitWhilePaused(live) { while (this.paused && live()) await sleep(150); }

  /** Shows the caption and narrates it; resolves when narration (or the minimum hold) ends. */
  showCaption(step, text, token, hold) {
    mount(this.caption, h('div.tour-cap-title', step.title || ''), h('div.tour-cap-text', text),
      h('div.tour-cap-foot', `Step ${this.i + 1} of ${this.steps.length}`, h('span', 'Space pause · ← → skip · Esc exit')));
    this.caption.classList.remove('hidden');
    this.placeCaption();
    const minMs = (hold ?? Math.max(2600, text.length * 55)) / this.speed;
    const timer = (async () => {
      let waited = 0;
      while (waited < minMs) { await sleep(100); if (!this.paused) waited += 100; if (token !== this.token || this.stopped) return; }
    })();
    if (!this.voice || !('speechSynthesis' in window)) return timer;
    const spoken = new Promise((resolve) => {
      speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text.replace(/[⚡✦◈⬡▤⚔💬📎🔊⏸⟲✚]/g, ''));
      u.rate = Math.min(2, 1.04 * this.speed); u.onend = resolve; u.onerror = resolve;
      speechSynthesis.speak(u);
      setTimeout(resolve, (text.length * 120 + 4000) / this.speed); // safety net if the engine never fires onend
    });
    return Promise.all([timer, spoken]);
  }

  // ---------------------------------------------------------------- helpers exposed to steps
  api() {
    const self = this;
    return {
      ctx: self.ctx,
      sleep,
      find: (sel, timeout) => self.find(sel, timeout),
      async click(sel) {
        const el = typeof sel === 'string' || typeof sel === 'function' ? await self.find(sel) : sel;
        el.scrollIntoView({ block: 'center' }); await sleep(250);
        await self.moveCursor(el); self.clickFx(); await sleep(180);
        if (typeof el.click === 'function') el.click();
        else el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); // SVG elements
        await sleep(450);
        return el;
      },
      async type(sel, text, { clear = true, cps = 38 } = {}) {
        const el = typeof sel === 'string' || typeof sel === 'function' ? await self.find(sel) : sel;
        el.focus(); if (clear) el.value = '';
        for (const ch of text) { el.value += ch; el.dispatchEvent(new Event('input', { bubbles: true })); await sleep(1000 / cps); }
      },
      highlight: async (sel) => { const el = await self.find(sel); self.highlight(el); await self.moveCursor(el); return el; },
      key(key, opts = {}) { document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, ...opts })); document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, ...opts })); },
      async waitFor(fn, timeout = 30000) { const t0 = Date.now(); while (Date.now() - t0 < timeout) { const v = await fn(); if (v) return v; await sleep(500); } throw new Error('Timed out waiting'); },
    };
  }

  /** Waits for an element: CSS selector, "selector::text" (first match containing text), or a function. */
  async find(sel, timeout = 9000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
      let el = null;
      if (typeof sel === 'function') el = sel();
      else if (sel.includes('::')) {
        const [css, txt] = sel.split('::');
        el = [...document.querySelectorAll(css)].find((e) => e.offsetParent !== null && e.textContent.includes(txt));
      } else el = [...document.querySelectorAll(sel)].find((e) => e.offsetParent !== null || e.getClientRects().length);
      if (el) return el;
      await sleep(150);
    }
    throw new Error(`Could not find "${typeof sel === 'string' ? sel : 'element'}" on this screen`);
  }

  highlight(el) {
    this.current = el;
    if (!el) { this.spot.classList.add('hidden'); this.caption.classList.add('center'); return; }
    this.caption.classList.remove('center');
    this.spot.classList.remove('hidden');
    this.reposition();
  }

  reposition() {
    if (this.stopped) return;
    const el = this.current;
    if (el && document.contains(el)) {
      const r = el.getBoundingClientRect(), pad = 8;
      Object.assign(this.spot.style, { left: `${r.left - pad}px`, top: `${r.top - pad}px`, width: `${r.width + pad * 2}px`, height: `${r.height + pad * 2}px` });
    }
    this.placeCaption();
  }

  placeCaption() {
    const c = this.caption;
    if (!this.current || !document.contains(this.current) || c.classList.contains('center')) {
      Object.assign(c.style, { left: '50%', top: '50%', transform: 'translate(-50%, -50%)' }); return;
    }
    const r = this.current.getBoundingClientRect();
    const cw = Math.min(420, window.innerWidth - 32), ch = c.offsetHeight || 160, gap = 18;
    let top = r.bottom + gap;
    if (top + ch > window.innerHeight - 90) top = r.top - ch - gap;
    if (top < 12) top = Math.min(window.innerHeight - ch - 90, Math.max(12, r.top + 20));
    let left = r.left + r.width / 2 - cw / 2;
    left = Math.max(16, Math.min(left, window.innerWidth - cw - 16));
    Object.assign(c.style, { left: `${left}px`, top: `${top}px`, transform: 'none', width: `${cw}px` });
  }

  async moveCursor(el) {
    const r = el.getBoundingClientRect();
    const x = r.left + Math.min(r.width / 2, 60), y = r.top + Math.min(r.height / 2, 30);
    this.cursor.classList.remove('hidden');
    this.cursor.style.transform = `translate(${x}px, ${y}px)`;
    await sleep(620);
  }

  clickFx() {
    const rp = this.cursor.querySelector('.tour-ripple');
    rp.classList.remove('go'); void rp.offsetWidth; rp.classList.add('go');
    window.dispatchEvent(new CustomEvent('af:pulse', { detail: { color: '#a78bfa' } }));
  }

  finish() {
    this.highlight(null);
    mount(this.caption, h('div.tour-cap-title', '🎉 That\'s the tour!'),
      h('div.tour-cap-text', 'You have seen every feature. Replay any chapter from the Help page, or press ☰ in this bar.'),
      h('div.row', { style: { marginTop: '12px' } },
        h('button.btn.primary.small', { onclick: () => { this.stop(); location.hash = '#/help'; } }, 'Open Help page'),
        h('button.btn.small', { onclick: () => this.stop() }, 'Close')));
    this.caption.classList.remove('hidden');
    this.placeCaption();
    if (this.voice && 'speechSynthesis' in window) speechSynthesis.speak(new SpeechSynthesisUtterance("That's the tour. You can replay any chapter from the Help page."));
  }

  stop() {
    this.stopped = true; this.token++;
    speechSynthesis?.cancel();
    [this.spot, this.cursor, this.caption, this.bar, this.chaptersMenu].forEach((e) => e.classList.add('hidden'));
    document.body.classList.remove('touring');
  }
}
