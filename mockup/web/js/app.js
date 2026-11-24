/* ============================================================
   vitals — cliente web real del agente (mockup de la app).
   Escanea el QR, empareja y consume la API a través del proxy
   local que verifica el pin del certificado.
   ============================================================ */
(() => {
  'use strict';

  /* ----------------------------- helpers ----------------------------- */
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  const fmtBytes = (n) => {
    if (!isFinite(n) || n <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    let v = n;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i += 1;
    }
    return `${v >= 100 ? v.toFixed(0) : v.toFixed(1)} ${units[i]}`;
  };
  const fmtRate = (n) => `${fmtBytes(n)}/s`;
  const fmtUptime = (secs) => {
    const d = Math.floor(secs / 86400);
    const h = Math.floor((secs % 86400) / 3600);
    const m = Math.floor((secs % 3600) / 60);
    return `${d > 0 ? `${d}d ` : ''}${h}h ${m}m`;
  };
  const setText = (sel, value) => {
    const el = $(sel);
    if (el && el.textContent !== String(value)) el.textContent = String(value);
  };
  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  class ApiError extends Error {
    constructor(status, message) {
      super(message);
      this.status = status;
    }
  }

  /* ------------------------------ estado ----------------------------- */
  const STORE_KEY = 'vitals.session.v2';
  const MAX_POINTS = 60;

  const S = {
    session: null,
    epIndex: 0,
    latency: null,
    transport: 'idle', // idle | sse | error
    metrics: null,
    host: null,
    info: null,
    series: { cpu: [], rx: [], tx: [] },
    history: null,
    netRange: 'live',
    procs: [],
    sort: 'cpu',
    filter: '',
    pending: null,
    screen: 'home',
    streamAbort: null,
    streamFailures: 0,
    pollTimer: null,
    procTimer: null,
    scan: { stream: null, timer: null },
  };

  const kindOf = (url) => {
    try {
      const host = new URL(url).hostname;
      if (host.startsWith('[')) return 'Tailscale';
      if (/^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(host)) return 'Tailscale';
      if (/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host)) return 'LAN';
      if (host === 'localhost' || host.startsWith('127.')) return 'Local';
      return 'Remoto';
    } catch (_) {
      return '—';
    }
  };

  /* --------------------------- persistencia -------------------------- */
  const loadSession = () => {
    try {
      return JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
    } catch (_) {
      return null;
    }
  };
  const saveSession = () => {
    if (S.session) localStorage.setItem(STORE_KEY, JSON.stringify(S.session));
  };
  const clearSession = () => {
    localStorage.removeItem(STORE_KEY);
    S.session = null;
  };

  /* ------------------------------ toast ------------------------------ */
  let toastTimer = null;
  function toast(message) {
    const node = $('#toast');
    node.textContent = message;
    node.classList.add('is-visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.remove('is-visible'), 2600);
  }

  /* ------------------------- API a través del proxy ------------------ */
  async function request(path, { method = 'GET', body, upstream, timeout = 8000, signal } = {}) {
    const ep = upstream || S.session.endpoints[S.epIndex];
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    if (signal) signal.addEventListener('abort', () => ctrl.abort(), { once: true });

    const headers = { 'X-Vitals-Upstream': ep, 'X-Vitals-Pin': S.session.pin };
    if (S.session.token) headers.Authorization = `Bearer ${S.session.token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    const t0 = performance.now();
    try {
      const res = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: ctrl.signal,
        cache: 'no-store',
      });
      S.latency = Math.round(performance.now() - t0);
      if (res.status === 401) throw new ApiError(401, 'Token rechazado por el host.');
      if (!res.ok) {
        let msg = `Error ${res.status}`;
        try {
          const payload = await res.json();
          if (payload && payload.error) msg = payload.error;
        } catch (_) {}
        throw new ApiError(res.status, msg);
      }
      return res;
    } catch (err) {
      if (err.name === 'AbortError') throw new ApiError(0, 'El host no respondió a tiempo.');
      throw err;
    } finally {
      clearTimeout(timer);
    }
  }

  const getJson = async (path, opts) => (await request(path, opts)).json();

  async function withFailover(path, opts = {}, { quiet = false } = {}) {
    const eps = S.session.endpoints;
    let lastErr = null;
    for (let i = 0; i < eps.length; i += 1) {
      const idx = (S.epIndex + i) % eps.length;
      try {
        const out = await getJson(path, { ...opts, upstream: eps[idx] });
        if (idx !== S.epIndex) {
          S.epIndex = idx;
          renderConnection();
          renderEndpoints();
          if (!quiet) toast(`Conectado por ${kindOf(eps[idx])}`);
        }
        return out;
      } catch (err) {
        lastErr = err;
        if (err.status === 401 || err.status === 400 || err.status === 403) throw err;
      }
    }
    throw lastErr || new ApiError(0, 'Ningún endpoint respondió.');
  }

  /* ------------------------------- SSE ------------------------------- */
  function handleSse(chunk) {
    const lines = chunk.split('\n');
    let event = 'message';
    const data = [];
    for (const line of lines) {
      if (!line || line.startsWith(':')) continue;
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).trim());
    }
    if (!data.length) return;
    if (event === 'snapshot') {
      try {
        applyMetrics(JSON.parse(data.join('\n')));
      } catch (_) {}
    } else if (event === 'resync') {
      refreshSnapshot();
    }
  }

  function startStream() {
    stopStream();
    if (!S.session) return;
    const ctrl = new AbortController();
    S.streamAbort = ctrl;
    const headers = {
      'X-Vitals-Upstream': S.session.endpoints[S.epIndex],
      'X-Vitals-Pin': S.session.pin,
      Authorization: `Bearer ${S.session.token}`,
    };

    fetch('/api/v1/stream', { headers, signal: ctrl.signal, cache: 'no-store' })
      .then(async (res) => {
        if (!res.ok || !res.body) throw new ApiError(res.status, 'stream rechazado');
        S.transport = 'sse';
        S.streamFailures = 0;
        stopPolling();
        renderConnection();
        const reader = res.body.getReader();
        const dec = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += dec.decode(value, { stream: true });
          let cut = buffer.indexOf('\n\n');
          while (cut >= 0) {
            handleSse(buffer.slice(0, cut));
            buffer = buffer.slice(cut + 2);
            cut = buffer.indexOf('\n\n');
          }
        }
        throw new ApiError(0, 'stream cerrado');
      })
      .catch((err) => {
        if (ctrl.signal.aborted) return;
        S.transport = 'error';
        renderConnection();
        startPolling();
        S.streamFailures += 1;
        if (S.streamFailures >= 2 && S.session.endpoints.length > 1) {
          S.epIndex = (S.epIndex + 1) % S.session.endpoints.length;
          renderConnection();
          renderEndpoints();
        }
        const delay = Math.min(1200 * S.streamFailures, 8000);
        setTimeout(() => {
          if (S.session && !ctrl.signal.aborted) startStream();
        }, delay);
        if (S.streamFailures === 2) toast(`Stream perdido (${err.message})`);
      });
  }

  function stopStream() {
    if (S.streamAbort) {
      S.streamAbort.abort();
      S.streamAbort = null;
    }
  }

  function startPolling() {
    if (S.pollTimer) return;
    S.pollTimer = setInterval(() => refreshSnapshot(true), 2500);
  }
  function stopPolling() {
    if (S.pollTimer) {
      clearInterval(S.pollTimer);
      S.pollTimer = null;
    }
  }

  async function refreshSnapshot(quiet = false) {
    if (!S.session) return;
    try {
      const sample = await withFailover('/api/v1/metrics', {}, { quiet });
      applyMetrics(sample);
    } catch (err) {
      if (err.status === 401) handleFatal(err);
    }
  }

  /* ----------------------------- métricas ---------------------------- */
  function push(arr, value) {
    arr.push(value);
    if (arr.length > MAX_POINTS) arr.shift();
  }

  function applyMetrics(sample) {
    S.metrics = sample;
    push(S.series.cpu, sample.cpu.total);
    push(S.series.rx, sample.net.rx_bps);
    push(S.series.tx, sample.net.tx_bps);
    renderHome();
    if (S.screen === 'cpu') renderCpu();
    if (S.screen === 'net') renderNet();
  }

  function renderHome() {
    const m = S.metrics;
    if (!m) return;

    setText('#cpu-total', Math.round(m.cpu.total));
    setText('#chip-load', `load ${m.cpu.load1.toFixed(2)}`);
    const temp = m.temps.length ? m.temps[0].celsius : null;
    setText('#chip-temp', temp === null ? 'sin sensor' : `${Math.round(temp)} °C`);

    const arc = $('#ring-arc');
    const circumference = 2 * Math.PI * 52;
    arc.style.strokeDashoffset = String(circumference * (1 - Math.min(m.cpu.total, 100) / 100));
    arc.style.stroke =
      m.cpu.total > 85 ? 'var(--tx)' : m.cpu.total > 60 ? 'var(--warn)' : 'var(--accent)';

    const pct = m.mem.total ? (m.mem.used / m.mem.total) * 100 : 0;
    setText('#mem-pct', `${pct.toFixed(0)}%`);
    $('#mem-used').style.width = `${pct.toFixed(1)}%`;
    $('#mem-cache').style.width = `${Math.max(0, 100 - pct).toFixed(1)}%`;
    setText('#mem-text', `${fmtBytes(m.mem.used)} de ${fmtBytes(m.mem.total)}`);
    setText(
      '#swap-text',
      m.mem.swap_total > 0 ? `swap ${fmtBytes(m.mem.swap_used)}` : 'sin swap'
    );

    setText('#net-rx', fmtRate(m.net.rx_bps));
    setText('#net-tx', fmtRate(m.net.tx_bps));
    setText('#net-total', fmtRate(m.net.rx_bps + m.net.tx_bps));

    const io = m.disks.reduce((acc, d) => acc + d.r_bps + d.w_bps, 0);
    setText('#disk-io', io > 0 ? fmtRate(io) : 'sin I/O');
    renderDisks(m.disks);

    setText('#uptime', `encendido hace ${fmtUptime(m.uptime)}`);

    Charts.draw(
      $('#spark-cpu'),
      [{ values: S.series.cpu, color: '#7bf1a8', fill: true }],
      { min: 15, capacity: MAX_POINTS }
    );
    Charts.draw(
      $('#spark-net'),
      [
        { values: S.series.rx, color: '#5aa9ff', fill: true },
        { values: S.series.tx, color: '#ff8f6b', fill: false },
      ],
      { capacity: MAX_POINTS }
    );
  }

  function renderDisks(disks) {
    const list = $('#disk-list');
    const mounts = disks.map((d) => d.mount).join('|');
    if (list.dataset.mounts !== mounts) {
      list.textContent = '';
      for (const disk of disks) {
        const wrap = el('div', 'disk');
        wrap.dataset.mount = disk.mount;
        const top = el('div', 'disk__top');
        top.append(el('span', 'disk__mount', disk.mount));
        const pct = disk.total ? (disk.used / disk.total) * 100 : 0;
        top.append(el('b', null, `${pct.toFixed(0)}% · ${fmtBytes(disk.used)}/${fmtBytes(disk.total)}`));
        const bar = el('div', 'disk__bar');
        bar.append(el('i'));
        const io = el('div', 'disk__io', '');
        wrap.append(top, bar, io);
        list.append(wrap);
      }
      list.dataset.mounts = mounts;
    }
    for (const disk of disks) {
      const wrap = list.querySelector(`.disk[data-mount="${CSS.escape(disk.mount)}"]`);
      if (!wrap) continue;
      const pct = disk.total ? (disk.used / disk.total) * 100 : 0;
      wrap.querySelector('.disk__bar i').style.width = `${pct.toFixed(1)}%`;
      wrap.querySelector('.disk__io').textContent =
        disk.r_bps + disk.w_bps > 0
          ? `R ${fmtRate(disk.r_bps)} · W ${fmtRate(disk.w_bps)}`
          : 'inactivo';
    }
  }

  function renderCpu() {
    const m = S.metrics;
    if (!m) return;

    setText('#cpu-now', `${m.cpu.total.toFixed(1)}% ahora`);
    setText('#cores-count', `${m.cpu.cores.length} núcleos`);
    setText('#load1', m.cpu.load1.toFixed(2));
    setText('#load5', m.cpu.load5.toFixed(2));
    setText('#load15', m.cpu.load15.toFixed(2));

    Charts.draw($('#chart-cpu'), [{ values: S.series.cpu, color: '#7bf1a8', fill: true }], {
      max: 100,
      grid: true,
      capacity: MAX_POINTS,
    });

    const values = S.series.cpu;
    if (values.length) {
      setText('#cpu-min', `mín ${Math.min(...values).toFixed(0)}%`);
      setText('#cpu-avg', `med ${(values.reduce((a, b) => a + b, 0) / values.length).toFixed(0)}%`);
      setText('#cpu-max', `máx ${Math.max(...values).toFixed(0)}%`);
    }

    const cores = $('#cores');
    if (cores.children.length !== m.cpu.cores.length) {
      cores.textContent = '';
      m.cpu.cores.forEach((_, i) => {
        const row = el('div', 'core');
        const top = el('div', 'core__top');
        top.append(el('span', null, `#${i}`), el('span', null, '0%'));
        const bar = el('div', 'core__bar');
        bar.append(el('i'));
        row.append(top, bar);
        cores.append(row);
      });
    }
    m.cpu.cores.forEach((value, i) => {
      const row = cores.children[i];
      if (!row) return;
      row.querySelector('.core__top span:last-child').textContent = `${Math.round(value)}%`;
      const fill = row.querySelector('.core__bar i');
      fill.style.width = `${Math.min(value, 100).toFixed(1)}%`;
      fill.className = value > 85 ? 'is-hot' : value > 60 ? 'is-warm' : '';
    });

    const temps = $('#temps');
    temps.textContent = '';
    if (!m.temps.length) {
      temps.append(el('span', 'muted', 'Este host no expone sensores legibles.'));
    } else {
      for (const t of m.temps) {
        const chip = el('span', 'chip', `${t.label} ${Math.round(t.celsius)} °C`);
        if (t.celsius > 80) chip.classList.add('chip--hot');
        else if (t.celsius > 65) chip.classList.add('chip--warm');
        temps.append(chip);
      }
    }
  }

  function renderNet() {
    const m = S.metrics;
    if (!m) return;

    setText('#net-rx-big', fmtBytes(m.net.rx_bps));
    setText('#net-tx-big', fmtBytes(m.net.tx_bps));

    const chart = $('#chart-net');
    if (S.netRange === 'live') {
      Charts.draw(
        chart,
        [
          { values: S.series.rx, color: '#5aa9ff', fill: true },
          { values: S.series.tx, color: '#ff8f6b', fill: false },
        ],
        { grid: true, capacity: MAX_POINTS }
      );
    } else if (S.history && S.history.points.length) {
      Charts.draw(
        chart,
        [
          { values: S.history.points.map((p) => p.net_rx), color: '#5aa9ff', fill: true },
          { values: S.history.points.map((p) => p.net_tx), color: '#ff8f6b', fill: false },
        ],
        { grid: true }
      );
    }

    const list = $('#iface-list');
    const names = m.net.ifaces.map((i) => i.name).join('|');
    if (list.dataset.names !== names) {
      list.textContent = '';
      for (const iface of m.net.ifaces) {
        const row = el('div', 'iface');
        row.dataset.name = iface.name;
        row.append(el('span', 'iface__name', iface.name), el('span', 'iface__rates', ''));
        list.append(row);
      }
      list.dataset.names = names;
    }
    for (const iface of m.net.ifaces) {
      const row = list.querySelector(`.iface[data-name="${CSS.escape(iface.name)}"]`);
      if (row) {
        row.querySelector('.iface__rates').textContent = `↓${fmtRate(iface.rx_bps)} ↑${fmtRate(iface.tx_bps)}`;
      }
    }
  }

  function renderProcs() {
    const list = $('#proc-list');
    const filter = S.filter.trim().toLowerCase();
    const rows = S.procs.filter((p) => !filter || p.name.toLowerCase().includes(filter));
    list.textContent = '';
    if (!rows.length) {
      list.append(el('div', 'proc', 'Sin procesos para este filtro.'));
      return;
    }
    const max = Math.max(...rows.map((p) => (S.sort === 'cpu' ? p.cpu : p.mem)), 1);
    for (const proc of rows) {
      const row = el('div', 'proc');
      if ((S.sort === 'cpu' ? proc.cpu : proc.mem / max) > 0.7) row.classList.add('is-hot');
      const left = el('div');
      left.append(el('div', 'proc__name', proc.name), el('div', 'proc__pid', `pid ${proc.pid}`));
      const value = S.sort === 'cpu' ? `${proc.cpu.toFixed(1)}%` : fmtBytes(proc.mem);
      row.append(left, el('div', 'proc__val', value));
      const bar = el('div', 'proc__bar');
      const fill = el('i');
      fill.style.width = `${(((S.sort === 'cpu' ? proc.cpu : proc.mem / max) / max) * 100 || 0).toFixed(1)}%`;
      if (S.sort === 'cpu') fill.style.width = `${Math.min(proc.cpu, 100).toFixed(1)}%`;
      bar.append(fill);
      row.append(bar);
      list.append(row);
    }
  }

  /* --------------------------- datos remotos -------------------------- */
  async function seedSeries() {
    try {
      const data = await withFailover('/api/v1/history?range=15m', {}, { quiet: true });
      const points = (data.points || []).slice(-MAX_POINTS);
      if (points.length < 2) return;
      S.series.cpu = points.map((p) => p.cpu);
      S.series.rx = points.map((p) => p.net_rx);
      S.series.tx = points.map((p) => p.net_tx);
      renderHome();
    } catch (_) {}
  }

  async function loadHistory(range) {
    try {
      S.history = await withFailover(`/api/v1/history?range=${encodeURIComponent(range)}`);
      renderNet();
    } catch (err) {
      toast(`Histórico no disponible: ${err.message}`);
    }
  }

  async function loadProcs() {
    if (!S.session) return;
    try {
      const query = new URLSearchParams({ sort: S.sort, limit: '20' });
      const data = await withFailover(`/api/v1/processes?${query}`, {}, { quiet: true });
      S.procs = data.processes || [];
      renderProcs();
    } catch (err) {
      if (err.status === 401) handleFatal(err);
    }
  }

  async function loadInfo() {
    try {
      S.info = await withFailover('/api/v1/info', {}, { quiet: true });
      renderSettings();
      renderEndpoints();
    } catch (_) {}
  }

  /* ----------------------------- settings ---------------------------- */
  function renderSettings() {
    if (!S.session) return;
    setText('#set-host', S.session.name);
    setText('#set-pin', S.session.pin);
    const token = S.session.token || '';
    setText('#set-token', token ? `${token.slice(0, 12)}…${token.slice(-4)}` : '—');
    setText('#set-version', S.info?.version || '—');

    const list = $('#device-list');
    list.textContent = '';
    const devices = S.info?.devices || [];
    if (!devices.length) {
      list.append(el('div', 'device muted', 'Sin dispositivos (¿sesión antigua?)'));
      return;
    }
    for (const dev of devices) {
      const row = el('div', 'device');
      const left = el('div');
      left.append(el('div', null, dev.name));
      left.append(el('div', 'muted', `id ${dev.id}`));
      const last = dev.last_seen_at
        ? new Date(dev.last_seen_at * 1000).toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' })
        : 'nunca';
      row.append(left, el('span', 'endpoint__tag', last));
      list.append(row);
    }
  }

  function renderEndpoints() {
    if (!S.session) return;
    const targets = [['#sheet-endpoints-list', true]];
    for (const [sel] of targets) {
      const list = $(sel);
      if (!list) continue;
      list.textContent = '';
      S.session.endpoints.forEach((url, i) => {
        const row = el('div', 'endpoint');
        if (i === S.epIndex) row.classList.add('is-active');
        row.append(el('span', 'endpoint__url', url.replace(/^https:\/\//, '').replace(/\/$/, '')));
        row.append(el('span', 'endpoint__tag', i === S.epIndex ? 'activo' : kindOf(url)));
        list.append(row);
      });
    }
    setText('#transport', S.transport === 'sse' ? 'SSE' : S.transport === 'error' ? 'polling' : '—');
    setText('#latency', S.latency === null ? '—' : `${S.latency} ms`);
  }

  function renderConnection() {
    const pill = $('#conn-pill');
    const ep = S.session?.endpoints[S.epIndex] || '';
    const kind = kindOf(ep);
    setText('#conn-label', kind);
    pill.classList.toggle('is-ts', kind === 'Tailscale');
    pill.classList.toggle('is-off', S.transport === 'error');
    pill.classList.toggle('is-warn', S.transport === 'idle');
  }

  /* ------------------------------ pantallas --------------------------- */
  function go(screen) {
    S.navTouched = true;
    S.screen = screen;
    $$('.screen').forEach((node) => node.classList.toggle('is-active', node.dataset.screen === screen));
    $$('.tab').forEach((tab) => tab.classList.toggle('is-active', tab.dataset.go === screen));
    $('#screens').scrollTop = 0;
    if (screen === 'cpu') renderCpu();
    if (screen === 'net') {
      renderNet();
      if (S.netRange !== 'live' && !S.history) loadHistory(S.netRange);
    }
    if (screen === 'procs') {
      loadProcs();
      if (!S.procTimer) S.procTimer = setInterval(() => {
        if (S.screen === 'procs' && !document.hidden) loadProcs();
      }, 4000);
    }
  }

  /* ----------------------------- onboarding -------------------------- */
  function showStep(step) {
    $$('.onboard__step').forEach((node) => {
      node.hidden = node.dataset.step !== step;
    });
    $('#brand').hidden = step !== 'intro';
  }

  function decodePayload(raw) {
    const text = String(raw || '').trim();
    const body = text.startsWith('vitals1:') ? text.slice(8) : text;
    const b64 = body.replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    let bytes;
    try {
      bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    } catch (_) {
      throw new Error('El código no es base64 válido.');
    }
    let json;
    try {
      json = JSON.parse(new TextDecoder().decode(bytes));
    } catch (_) {
      throw new Error('El código no contiene un JSON válido.');
    }
    if (json.v !== 1) throw new Error(`Versión de payload no soportada (${json.v}).`);
    if (!Array.isArray(json.ep) || json.ep.length === 0) throw new Error('El payload no trae endpoints.');
    if (!json.pin || !json.code) throw new Error('El payload está incompleto.');
    return json;
  }

  function acceptPayload(raw) {
    let payload;
    try {
      payload = decodePayload(raw);
    } catch (err) {
      toast(err.message);
      return;
    }
    S.pending = payload;
    const left = Math.max(0, (payload.exp || 0) - Math.floor(Date.now() / 1000));
    setText('#found-host', payload.name || 'host');
    setText('#found-endpoints', `${payload.ep.length} · ${kindOf(payload.ep[0])}`);
    setText('#found-pin', payload.pin);
    setText('#found-code', payload.code);
    setText('#found-exp', left > 0 ? `${Math.floor(left / 60)}m ${left % 60}s` : 'caducado');
    showStep('found');
  }

  async function pairWithFailover(payload) {
    const deviceName = 'web-mockup';
    let lastErr = null;
    for (let i = 0; i < payload.ep.length; i += 1) {
      const url = payload.ep[i];
      try {
        const data = await getJson('/api/v1/pair', {
          method: 'POST',
          upstream: url,
          body: { code: payload.code, device_name: deviceName },
        });
        S.epIndex = i;
        return data;
      } catch (err) {
        lastErr = err;
        if (err.status === 401) throw err;
      }
    }
    throw lastErr || new ApiError(0, 'Ningún endpoint aceptó la conexión.');
  }

  async function doPair() {
    if (!S.pending) return;
    showStep('pairing');
    $('#pairing-text').textContent = 'Intercambiando el código por un token…';
    try {
      const data = await pairWithFailover(S.pending);
      S.session = {
        name: S.pending.name || 'host',
        pin: S.pending.pin,
        endpoints: S.pending.ep,
        token: data.token,
        deviceId: data.device_id,
        pairedAt: Date.now(),
      };
      saveSession();
      setText('#done-text', `${S.session.name} · ${S.session.endpoints.length} endpoint(s)`);
      showStep('done');
    } catch (err) {
      setText('#err-title', err.status === 401 ? 'Código rechazado' : 'No se pudo conectar');
      setText(
        '#err-text',
        `${err.message} Si el código caducó, genera otro con "vitals pair" en el host.`
      );
      showStep('error');
    }
  }

  async function enterApp() {
    $('#onboard').hidden = true;
    $('#shell').hidden = false;
    renderConnection();
    try {
      const host = await withFailover('/api/v1/host', {}, { quiet: true });
      S.host = host;
      setText('#host-name', host.hostname);
      setText('#host-os', host.os);
      setText('#cpu-model', host.cpu_model || '');
      document.title = `${host.hostname} · vitals`;
      applyMetrics(await withFailover('/api/v1/metrics', {}, { quiet: true }));
      loadInfo();
      await seedSeries();
      startStream();
      // Do not yank the user back to Inicio if they already tapped a tab
      // while the first snapshot was loading.
      if (!S.navTouched) go('home');
    } catch (err) {
      handleFatal(err);
    }
  }

  function handleFatal(err) {
    stopStream();
    stopPolling();
    if (err.status === 401) {
      clearSession();
      setText('#err-title', 'La sesión ya no es válida');
      setText('#err-text', 'El token fue revocado o el host cambió de certificado. Vuelve a emparejar.');
    } else {
      setText('#err-title', 'No se pudo alcanzar el host');
      setText('#err-text', err.message);
    }
    $('#shell').hidden = true;
    $('#onboard').hidden = false;
    showStep('error');
  }

  /* ------------------------------ escáner ---------------------------- */
  function scannerFallback(reason) {
    const fallback = $('#scan-fallback');
    fallback.hidden = false;
    if (reason) setText('#scan-hint', reason);
    $('#scanner-idle').hidden = false;
  }

  function stopScanner() {
    if (S.scan.timer) {
      clearInterval(S.scan.timer);
      S.scan.timer = null;
    }
    if (S.scan.stream) {
      S.scan.stream.getTracks().forEach((t) => t.stop());
      S.scan.stream = null;
    }
    const video = $('#scanner-video');
    video.srcObject = null;
    $('#scanner-idle').hidden = false;
  }

  async function openScanner() {
    showStep('scan');
    $('#scan-fallback').hidden = true;
    const video = $('#scanner-video');

    if (!window.isSecureContext) {
      scannerFallback(
        'La cámara necesita HTTPS. Abre esta página por Tailscale (https://…) o usa una foto o el código pegado.'
      );
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      scannerFallback('Este navegador no expone la cámara a la página.');
      return;
    }

    try {
      S.scan.stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } },
        audio: false,
      });
      video.srcObject = S.scan.stream;
      await video.play();
      $('#scanner-idle').hidden = true;
      setText('#scan-hint', 'Apunta al QR que muestra el host.');
    } catch (err) {
      scannerFallback(`No se pudo abrir la cámara (${err.name}).`);
      return;
    }

    if (!('BarcodeDetector' in window)) {
      scannerFallback('Este navegador no decodifica QR en la página. Usa una foto o pega el código.');
      return;
    }

    const detector = new BarcodeDetector({ formats: ['qr_code'] });
    S.scan.timer = setInterval(async () => {
      if (!video.videoWidth) return;
      try {
        const codes = await detector.detect(video);
        const hit = codes.find((c) => c.rawValue && c.rawValue.startsWith('vitals1:'));
        if (hit) {
          stopScanner();
          acceptPayload(hit.rawValue);
        }
      } catch (_) {}
    }, 220);
  }

  async function decodeImage(file) {
    if (!('BarcodeDetector' in window)) {
      toast('Este navegador no puede decodificar imágenes QR.');
      return;
    }
    try {
      const bitmap = await createImageBitmap(file);
      const codes = await new BarcodeDetector({ formats: ['qr_code'] }).detect(bitmap);
      const hit = codes.find((c) => c.rawValue && c.rawValue.startsWith('vitals1:'));
      if (!hit) {
        toast('No encontré un QR de vitals en la imagen.');
        return;
      }
      acceptPayload(hit.rawValue);
    } catch (err) {
      toast(`No se pudo leer la imagen: ${err.message}`);
    }
  }

  /* ------------------------------- eventos --------------------------- */
  function bind() {
    $('#btn-scan').addEventListener('click', openScanner);
    $('#btn-cancel-scan').addEventListener('click', () => {
      stopScanner();
      showStep('intro');
    });
    $('#btn-back-intro')?.addEventListener('click', () => showStep('intro'));
    $('#btn-rescan').addEventListener('click', () => {
      S.pending = null;
      openScanner();
    });
    $('#btn-pair').addEventListener('click', doPair);
    $('#btn-enter').addEventListener('click', enterApp);
    $('#btn-retry-pair').addEventListener('click', () => (S.pending ? openScanner() : showStep('intro')));
    $('#btn-restart-onboard').addEventListener('click', () => {
      stopScanner();
      S.pending = null;
      showStep('intro');
    });
    $('#btn-manual').addEventListener('click', () => {
      const value = $('#manual-payload').value.trim();
      if (!value) {
        toast('Pega primero el código.');
        return;
      }
      acceptPayload(value);
    });
    $('#btn-scan-manual').addEventListener('click', () => {
      const value = $('#scan-payload').value.trim();
      if (!value) {
        toast('Pega primero el código.');
        return;
      }
      stopScanner();
      acceptPayload(value);
    });
    $('#qr-file').addEventListener('change', (event) => {
      const file = event.target.files?.[0];
      if (file) decodeImage(file);
    });

    $('#tabbar').addEventListener('click', (event) => {
      const tab = event.target.closest('.tab');
      if (tab) go(tab.dataset.go);
    });

    $('#net-range').addEventListener('click', (event) => {
      const btn = event.target.closest('button');
      if (!btn) return;
      $$('#net-range button').forEach((b) => b.classList.toggle('is-active', b === btn));
      S.netRange = btn.dataset.range;
      if (S.netRange === 'live') renderNet();
      else loadHistory(S.netRange);
    });

    $('#proc-sort').addEventListener('click', (event) => {
      const btn = event.target.closest('button');
      if (!btn) return;
      $$('#proc-sort button').forEach((b) => b.classList.toggle('is-active', b === btn));
      S.sort = btn.dataset.sort;
      renderProcs();
      loadProcs();
    });

    $('#proc-search').addEventListener('input', (event) => {
      S.filter = event.target.value;
      renderProcs();
    });

    const openSheet = (id) => {
      $(id).hidden = false;
    };
    $('#conn-pill').addEventListener('click', () => {
      renderEndpoints();
      openSheet('#sheet-endpoints');
    });
    $('#settings-btn').addEventListener('click', () => {
      renderSettings();
      loadInfo();
      openSheet('#sheet-settings');
    });
    $('#host-btn').addEventListener('click', () => {
      renderSettings();
      openSheet('#sheet-settings');
    });
    $$('[data-close-sheet]').forEach((btn) =>
      btn.addEventListener('click', () => {
        btn.closest('.sheet').hidden = true;
      })
    );
    $$('.sheet').forEach((sheet) =>
      sheet.addEventListener('click', (event) => {
        if (event.target === sheet) sheet.hidden = true;
      })
    );

    $('#btn-reconnect').addEventListener('click', async () => {
      toast('Reconectando…');
      startStream();
      await refreshSnapshot(true);
      renderEndpoints();
    });

    $('#btn-reset').addEventListener('click', () => {
      stopStream();
      stopPolling();
      clearSession();
      location.reload();
    });

    document.addEventListener('visibilitychange', () => {
      if (document.hidden) return;
      if (S.session && S.transport !== 'sse') {
        startStream();
        refreshSnapshot(true);
      }
    });
  }

  /* -------------------------------- arranque ------------------------- */
  function boot() {
    bind();
    const saved = loadSession();
    if (saved && saved.token && saved.endpoints?.length) {
      S.session = saved;
      enterApp();
      return;
    }
    showStep('intro');
  }

  boot();
})();
