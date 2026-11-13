// Live wallpaper: drifting aurora, a neural particle mesh that reacts to the pointer,
// a perspective grid floor, and energy pulses fired by real agent events.
// Pauses when the tab is hidden; honours prefers-reduced-motion and the Motion toggle.

const AGENT_HUES = ['#6366f1', '#0ea5e9', '#14b8a6', '#f59e0b', '#ef4444', '#a855f7'];

export function startWallpaper(canvas) {
  const ctx = canvas.getContext('2d');
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let W = 0, H = 0, dpr = 1, nodes = [], pulses = [], bursts = [], t = 0, raf = 0, running = false;
  const mouse = { x: -9999, y: -9999, active: false };
  let enabled = readPref();

  const blobs = [
    { hue: '#4f46e5', r: 0.55, x: 0.15, y: 0.2, sx: 0.00011, sy: 0.00007, ph: 0 },
    { hue: '#7c3aed', r: 0.5, x: 0.85, y: 0.25, sx: 0.00008, sy: 0.00012, ph: 2 },
    { hue: '#0891b2', r: 0.45, x: 0.6, y: 0.85, sx: 0.0001, sy: 0.00009, ph: 4 },
    { hue: '#db2777', r: 0.35, x: 0.25, y: 0.8, sx: 0.00013, sy: 0.00006, ph: 1 },
  ];

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 1.5);
    W = window.innerWidth; H = window.innerHeight;
    canvas.width = W * dpr; canvas.height = H * dpr;
    canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const count = Math.min(140, Math.round((W * H) / 13000));
    nodes = Array.from({ length: count }, (_, i) => ({
      x: Math.random() * W, y: Math.random() * H,
      vx: (Math.random() - 0.5) * 0.25, vy: (Math.random() - 0.5) * 0.25,
      r: Math.random() * 1.6 + 0.6, hue: AGENT_HUES[i % AGENT_HUES.length], tw: Math.random() * Math.PI * 2,
    }));
  }

  function drawAurora() {
    ctx.fillStyle = '#05060f';
    ctx.fillRect(0, 0, W, H);
    ctx.globalCompositeOperation = 'lighter';
    for (const b of blobs) {
      const x = (b.x + Math.sin(t * b.sx * 60 + b.ph) * 0.12) * W;
      const y = (b.y + Math.cos(t * b.sy * 60 + b.ph) * 0.12) * H;
      const r = b.r * Math.max(W, H);
      const g = ctx.createRadialGradient(x, y, 0, x, y, r);
      g.addColorStop(0, hexA(b.hue, 0.22));
      g.addColorStop(0.45, hexA(b.hue, 0.07));
      g.addColorStop(1, hexA(b.hue, 0));
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  function drawGrid() {
    // Perspective floor in the lower third, scrolling slowly toward the viewer.
    const horizon = H * 0.62, vp = W / 2;
    ctx.save();
    const fade = ctx.createLinearGradient(0, horizon, 0, H);
    fade.addColorStop(0, 'rgba(99,102,241,0)');
    fade.addColorStop(1, 'rgba(99,102,241,0.22)');
    ctx.strokeStyle = fade; ctx.lineWidth = 1;
    for (let i = -24; i <= 24; i++) {
      ctx.beginPath(); ctx.moveTo(vp, horizon); ctx.lineTo(vp + i * W * 0.09, H); ctx.stroke();
    }
    const off = (t * 0.6) % 40;
    for (let k = 0; k < 18; k++) {
      const z = (k * 40 + off) / 720;
      const y = horizon + (H - horizon) * z * z;
      ctx.globalAlpha = Math.min(1, z * 1.4);
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke();
    }
    ctx.restore();
  }

  function drawMesh() {
    const link = Math.min(170, Math.max(110, W / 11));
    for (const n of nodes) {
      n.x += n.vx; n.y += n.vy; n.tw += 0.03;
      if (n.x < -20) n.x = W + 20; if (n.x > W + 20) n.x = -20;
      if (n.y < -20) n.y = H + 20; if (n.y > H + 20) n.y = -20;
      if (mouse.active) {
        const dx = mouse.x - n.x, dy = mouse.y - n.y, d = Math.hypot(dx, dy);
        if (d < 200 && d > 1) { n.x += (dx / d) * 0.35; n.y += (dy / d) * 0.35; }
      }
    }
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i];
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodes[j], dx = a.x - b.x, dy = a.y - b.y, d2 = dx * dx + dy * dy;
        if (d2 < link * link) {
          const o = (1 - Math.sqrt(d2) / link) * 0.32;
          ctx.strokeStyle = `rgba(148,163,255,${o})`;
          ctx.lineWidth = 0.7;
          ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
          if (Math.random() < 0.00025 && pulses.length < 40) pulses.push({ a, b, k: 0, hue: a.hue });
        }
      }
      if (mouse.active) {
        const d = Math.hypot(mouse.x - a.x, mouse.y - a.y);
        if (d < 190) {
          ctx.strokeStyle = `rgba(167,139,250,${(1 - d / 190) * 0.55})`;
          ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(mouse.x, mouse.y); ctx.stroke();
        }
      }
    }
    for (const n of nodes) {
      const glow = 0.55 + Math.sin(n.tw) * 0.35;
      ctx.fillStyle = hexA(n.hue, glow);
      ctx.beginPath(); ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2); ctx.fill();
    }
  }

  function drawPulses() {
    ctx.globalCompositeOperation = 'lighter';
    pulses = pulses.filter((p) => (p.k += 0.018) < 1);
    for (const p of pulses) {
      const x = p.a.x + (p.b.x - p.a.x) * p.k, y = p.a.y + (p.b.y - p.a.y) * p.k;
      const g = ctx.createRadialGradient(x, y, 0, x, y, 9);
      g.addColorStop(0, hexA(p.hue, 0.95)); g.addColorStop(1, hexA(p.hue, 0));
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.fill();
    }
    bursts = bursts.filter((b) => (b.k += 0.012) < 1);
    for (const b of bursts) {
      ctx.strokeStyle = hexA(b.hue, (1 - b.k) * 0.6);
      ctx.lineWidth = 2 * (1 - b.k) + 0.5;
      ctx.beginPath(); ctx.arc(b.x, b.y, 20 + b.k * 260, 0, Math.PI * 2); ctx.stroke();
    }
    ctx.globalCompositeOperation = 'source-over';
  }

  function frame() {
    if (!running) return;
    t += 1;
    drawAurora(); drawGrid(); drawMesh(); drawPulses();
    raf = requestAnimationFrame(frame);
  }

  function drawStatic() {
    t = 400; drawAurora(); drawGrid();
    for (const n of nodes) { ctx.fillStyle = hexA(n.hue, 0.6); ctx.beginPath(); ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2); ctx.fill(); }
  }

  function start() {
    if (running) return;
    if (reduced || !enabled) { drawStatic(); return; }
    running = true; raf = requestAnimationFrame(frame);
  }
  function stop() { running = false; cancelAnimationFrame(raf); }

  /** Fire an energy burst (used when real agent events arrive). */
  function pulse(color, at) {
    if (!running) return;
    const x = at?.x ?? W * (0.2 + Math.random() * 0.6), y = at?.y ?? H * (0.2 + Math.random() * 0.5);
    bursts.push({ x, y, k: 0, hue: color || AGENT_HUES[Math.floor(Math.random() * 6)] });
    // Light up the mesh links nearest the burst.
    const near = nodes.map((n) => [n, Math.hypot(n.x - x, n.y - y)]).sort((a, b) => a[1] - b[1]).slice(0, 8);
    for (let i = 0; i + 1 < near.length; i++) pulses.push({ a: near[i][0], b: near[i + 1][0], k: 0, hue: color || '#a78bfa' });
  }

  window.addEventListener('resize', () => { resize(); if (!running) drawStatic(); });
  window.addEventListener('pointermove', (e) => { mouse.x = e.clientX; mouse.y = e.clientY; mouse.active = true; }, { passive: true });
  window.addEventListener('pointerleave', () => (mouse.active = false));
  window.addEventListener('pointerdown', (e) => pulse(null, { x: e.clientX, y: e.clientY }));
  document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));
  window.addEventListener('af:pulse', (e) => pulse(e.detail?.color));

  resize(); start();

  return {
    get enabled() { return enabled; },
    setEnabled(on) {
      enabled = on;
      try { localStorage.setItem('af-motion', on ? '1' : '0'); } catch { /* ignore */ }
      if (on) start(); else { stop(); drawStatic(); }
    },
    pulse,
  };
}

function readPref() {
  try { return localStorage.getItem('af-motion') !== '0'; } catch { return true; }
}

function hexA(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}
