/* Minimal canvas charts: no dependencies, one pass per frame. */
(function () {
  'use strict';

  function setup(canvas) {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = canvas.clientWidth || 300;
    const h = canvas.clientHeight || 80;
    const bw = Math.round(w * dpr);
    const bh = Math.round(h * dpr);
    if (canvas.width !== bw || canvas.height !== bh) {
      canvas.width = bw;
      canvas.height = bh;
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    return { ctx, w, h };
  }

  function niceMax(values, floor) {
    let m = floor || 1;
    for (const v of values) if (v > m) m = v;
    const pad = m * 1.18;
    const mag = Math.pow(10, Math.floor(Math.log10(pad)));
    return Math.ceil(pad / mag) * mag;
  }

  /**
   * series: [{ values: number[], color: string, fill?: boolean }]
   * opts:  { max?: number, min?: number, grid?: boolean }
   */
  function draw(canvas, series, opts) {
    opts = opts || {};
    const { ctx, w, h } = setup(canvas);
    const padTop = 6;
    const padBottom = opts.grid ? 6 : 2;
    const usableH = h - padTop - padBottom;

    let max = opts.max;
    if (!max) {
      const all = [];
      for (const s of series) for (const v of s.values) all.push(v);
      max = niceMax(all, opts.min || 1);
    }
    max = Math.max(max, 0.0001);

    if (opts.grid) {
      ctx.strokeStyle = 'rgba(255,255,255,0.05)';
      ctx.lineWidth = 1;
      for (let i = 1; i <= 3; i++) {
        const y = Math.round(padTop + (usableH * i) / 4) + 0.5;
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(w, y);
        ctx.stroke();
      }
    }

    // `capacity` right-aligns a live series: new points enter from the right
    // and the line grows leftwards until the buffer is full.
    const capacity = Math.max(
      opts.capacity || 0,
      ...series.map((s) => s.values.length),
      2
    );

    for (const s of series) {
      const vals = s.values;
      if (!vals.length) continue;
      const step = w / Math.max(capacity - 1, 1);
      const offset = w - step * (vals.length - 1);
      const pts = vals.map((v, i) => [
        offset + i * step,
        padTop + usableH - Math.min(v / max, 1) * usableH,
      ]);

      if (s.fill !== false) {
        const grad = ctx.createLinearGradient(0, padTop, 0, h);
        grad.addColorStop(0, hexA(s.color, 0.28));
        grad.addColorStop(1, hexA(s.color, 0));
        ctx.beginPath();
        ctx.moveTo(pts[0][0], h);
        for (const p of pts) ctx.lineTo(p[0], p[1]);
        ctx.lineTo(pts[pts.length - 1][0], h);
        ctx.closePath();
        ctx.fillStyle = grad;
        ctx.fill();
      }

      ctx.beginPath();
      ctx.lineWidth = 1.8;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.strokeStyle = s.color;
      ctx.shadowColor = hexA(s.color, 0.45);
      ctx.shadowBlur = 8;
      if (pts.length === 1) {
        ctx.moveTo(pts[0][0], pts[0][1]);
        ctx.lineTo(pts[0][0] + 1, pts[0][1]);
      } else {
        ctx.moveTo(pts[0][0], pts[0][1]);
        for (let i = 1; i < pts.length - 1; i++) {
          const xc = (pts[i][0] + pts[i + 1][0]) / 2;
          const yc = (pts[i][1] + pts[i + 1][1]) / 2;
          ctx.quadraticCurveTo(pts[i][0], pts[i][1], xc, yc);
        }
        const last = pts[pts.length - 1];
        ctx.lineTo(last[0], last[1]);
      }
      ctx.stroke();
      ctx.shadowBlur = 0;
    }
  }

  function hexA(hex, a) {
    const n = parseInt(hex.replace('#', ''), 16);
    const r = (n >> 16) & 255;
    const g = (n >> 8) & 255;
    const b = n & 255;
    return `rgba(${r},${g},${b},${a})`;
  }

  window.Charts = { draw, niceMax };
})();
