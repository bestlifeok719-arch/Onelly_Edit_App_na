/* ===== Onelly Edit - TURBO export (level 2) =====
   FULLY INDEPENDENT of Fast Export and Standard Export: own button, own plugin (TurboPlugin.java: files, render, save), own progress screen.
   It only READS the project from index.html (clips, ovs, aud, bgm, FADE, PRESETS, OE, ovState, ovStatic, ovPieces, txDraw, txMoveCv, txAnim, total).
   Level 3 = ONE clip (cut) + still text/sticker/photo overlays + (not moving) overlay video with its sound + moving / fading / popping / sliding pictures + color filter.
   If Turbo cannot do the project, or anything goes wrong, it says why and STOPS: it never gives a wrong video and never starts another export.
   NOT compiled / tested on a device. */
(function () {
  'use strict';
  var C = window.Capacitor;
  if (!C || !C.isNativePlatform || !C.isNativePlatform()) return; /* browser: nothing to do */

  var TXT = {
    en: { b: '🚀 Turbo', no: 'Turbo is not possible for this project:', std: '', prep: 'Preparing…', go: 'Turbo GPU', miss: 'Turbo is not in this APK yet' },
    om: { b: '🚀 Turbo', no: 'Turbo project kanaaf hin danda’amu:', std: '', prep: 'Qophaa’aa jira…', go: 'Turbo GPU', miss: 'Turbo APK kana keessa hin jiru' }
  };
  function X(k) { var l = (typeof lang !== 'undefined' && TXT[lang]) ? lang : 'en'; return TXT[l][k]; }
  function $q(s) { return document.querySelector(s); }
  function plug() { var P = C.Plugins || {}; return { T: P.Turbo }; }

  /* ---------- 1) can Turbo level 1 do this project? returns '' (yes) or the reason (no) ---------- */
  function refuse() {
    if (!clips.length) return 'no clip';
    if (bgm && bgm.t !== 'blur' && bgm.t !== 'color') return 'background type ' + bgm.t;
    var OKK = { url: 1, dur: 1, 'in': 1, out: 1, speed: 1, rot: 1, flip: 1, vol: 1, f: 1, name: 1, id: 1, zx: 1, zy: 1, zm: 1, ang: 1, kb: 1, kf: 1, fx: 1, tr: 1, cg: 1, cgid: 1, _cgk: 1, sharp: 1, mb: 1, lut: 1, mk: 1, cv: 1 };
    var NAMED = {};
    for (var ci = 0; ci < clips.length; ci++) {
      var c = clips[ci], tag = clips.length > 1 ? 'clip ' + (ci + 1) + ': ' : '';
      for (var k0 in c) {
        var v0 = c[k0];
        if (!v0 || (typeof v0 === 'object' && !Object.keys(v0).length)) continue;
        if (NAMED[k0]) return tag + NAMED[k0];
        if (!OKK[k0]) return tag + 'clip effect (' + k0 + ')';
      }
      var sp = c.speed == null ? 1 : c.speed, rt = ((c.rot || 0) % 360 + 360) % 360;
      if (!c.cv && !(sp > 0.1 && sp <= 16)) return tag + 'clip speed ' + sp + '× (Turbo handles 0.1× to 16×)';
      if (c.cv) { var cmx = curveParts(c); if (cmx.bad) return tag + 'speed curve goes outside 0.1× to 16× (' + cmx.bad.toFixed(2) + '×)'; }
      if (rt % 90 !== 0) return tag + 'clip rotation ' + c.rot + '° (only 0/90/180/270)';
      var fx = c.fx || {};
      for (var fk in fx) { if (fx[fk] && ['vig', 'grn', 'shake', 'glit', 'blur', 'mos'].indexOf(fk) < 0) return tag + 'effect ' + fk + ' (not in Turbo yet)'; }
      var tr = c.tr;
      if (tr && tr.type && ['none', 'dip', 'slide', 'zoom', 'wipe'].indexOf(tr.type) < 0) return tag + 'transition ' + tr.type + ' (level 5)';
    }
    for (var i = 0; i < ovs.length; i++) {
      var o = ovs[i], el = OE[o.id];
      if (!el) return 'overlay missing';
      var isV = o.kind === 'vid' || (el.querySelector && el.querySelector('video'));
      if (isV) {
        if (!o.val) return 'overlay video file missing';
      }
      var BMOK = ['screen', 'multiply', 'overlay', 'soft-light', 'lighten', 'darken', 'difference', 'plus-lighter'];
      if (o.bm && BMOK.indexOf(o.bm) < 0) return 'blend mode ' + o.bm;
      if (o.ck && o.ck.on && !isV && isAnim(o)) return 'chroma key on a moving picture (level 6)';
      var ts = o.ts || {};
      if (ts.wd || ts.ai === 'type') {
        if (ts.mv || (ts.ao && ts.ao !== 'none') || (o.kf && o.kf.length) || (ts.wd && ts.ai && ts.ai !== 'none')) return 'typing / word-by-word text together with movement or fade-out (level 6)';
        if (o.kind !== 'text' && o.kind !== 'emoji') return 'typing on a picture';
      }
    }
    return '';
  }

  /* ---------- helpers ---------- */
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  function b64(blob) { return new Promise(function (res, rej) { var fr = new FileReader(); fr.onload = function () { res(String(fr.result).split(',')[1] || ''); }; fr.onerror = function () { rej(fr.error); }; fr.readAsDataURL(blob); }); }
  /* ---- Turbo's own progress screen ---- */
  var UI = { el: null, cancel: false, paused: false };
  async function gate() { while (UI.paused && !UI.cancel) await sleep(200); if (UI.cancel) throw new Error('cancelled'); }
  function ui(html) {
    if (!UI.el) {
      var d = document.createElement('div'); d.id = 'turboUI';
      d.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.88);color:#fff;display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif;padding:20px;box-sizing:border-box';
      d.innerHTML = '<div style="width:100%;max-width:420px;text-align:center"><div style="font-size:42px;font-weight:700" id="tbp">0%</div><div style="height:8px;background:#333;border-radius:4px;margin:12px 0;overflow:hidden"><div id="tbb" style="height:100%;width:0;background:#7c5cff"></div></div><div id="tbs" style="font-size:16px;margin:6px 0"></div><div id="tbe" style="font-size:13px;opacity:.7;min-height:18px"></div><div id="tbi" style="font-size:12px;opacity:.6;margin:8px 0;word-break:break-word"></div><video id="tbv" controls playsinline style="display:none;width:100%;border-radius:8px;margin:8px 0;background:#000"></video><div id="tba" style="display:flex;gap:8px;justify-content:center;flex-wrap:wrap;margin-top:12px"></div></div>';
      document.body.appendChild(d); UI.el = d;
    }
    return UI.el;
  }
  function btn(label, fn, main) { var b = document.createElement('button'); b.textContent = label; b.style.cssText = 'padding:11px 18px;border-radius:10px;border:0;font-size:15px;color:#fff;background:' + (main ? '#7c5cff' : '#444'); b.onclick = fn; return b; }
  function actions(list) { var a = $q('#tba'); a.innerHTML = ''; list.forEach(function (x) { a.appendChild(x); }); }
  function status(s) { var e = $q('#tbs'); if (e) e.textContent = s; }
  function info(s) { var e = $q('#tbi'); if (e) e.textContent = s; }
  function setP(p) { var v = Math.round(Math.max(0, Math.min(1, p)) * 100); var a = $q('#tbp'), b = $q('#tbb'); if (a) a.textContent = v + '%'; if (b) b.style.width = v + '%'; }
  function closeUI() { if (UI.el) { var v = $q('#tbv'); try { if (v) { v.pause(); v.removeAttribute('src'); } } catch (_) { } UI.el.remove(); UI.el = null; } }
  function fail(why) { ui(); setP(0); status('🚀 Turbo: ' + why); var e = $q('#tbe'); if (e) e.textContent = ''; actions([btn('OK', closeUI, true)]); }
  function secs(ms) { return Math.round((ms || 0) / 1000) + 's'; }

  /* copy a video into the app cache (native code needs a real file). Turbo's own copy; reuses it when nothing changed. */
  async function copySource(T, blob, url, onp, nm) {
    nm = nm || 'tsrc0.mp4';
    window.__turboSrc = window.__turboSrc || {};
    try {
      var t0 = window.__turboSrc[nm];
      if (t0 && t0.u === url && t0.size === blob.size) { var r1 = await T.stat({ path: nm }); if (r1 && r1.exists && r1.size === blob.size) { onp(1); return nm; } }
    } catch (_) { }
    delete window.__turboSrc[nm];
    await T.remove({ path: nm });
    try { /* the video is copied into the app's own storage: say it clearly when there is not enough room */
      var sp = await T.space();
      if (sp && sp.free >= 0 && sp.free < blob.size * 1.05 + 400e6) throw new Error('Not enough free storage on the phone: this video needs about ' + (blob.size / 1e9).toFixed(1) + ' GB of working space, only ' + (sp.free / 1e9).toFixed(1) + ' GB is free. Free some space and try again.');
    } catch (e) { if (/storage/.test(String(e && e.message))) throw e; }
    var done = false;
    try { /* fast way: raw bytes over 127.0.0.1 in 48 MB pieces (no base64); pausing works between pieces */
      var u = await T.upInfo(), PC = 50331648;
      if (u && u.port) {
        var pcs = [], loaded = [];
        for (var o = 0; o < blob.size; o += PC) { pcs.push([o, Math.min(blob.size, o + PC)]); loaded.push(0); }
        var sendPiece = function (idx) {
          return new Promise(function (res, rej) {
            var x = new XMLHttpRequest(), o0 = pcs[idx][0];
            x.open('POST', 'http://127.0.0.1:' + u.port + '/put?name=' + encodeURIComponent(nm) + '&t=' + u.token + '&pos=' + o0);
            x.upload.onprogress = function (e) { loaded[idx] = e.loaded; var sum = 0; for (var q = 0; q < loaded.length; q++) sum += loaded[q]; onp(sum / blob.size); };
            var iv = setInterval(function () { if (UI.cancel) { try { x.abort(); } catch (_) { } } }, 300);
            x.onload = function () { clearInterval(iv); x.status === 200 ? res() : rej(new Error('upload ' + x.status)); };
            x.onerror = x.onabort = x.ontimeout = function () { clearInterval(iv); rej(new Error(UI.cancel ? 'cancelled' : 'upload network')); };
            x.send(blob.slice(pcs[idx][0], pcs[idx][1]));
          });
        };
        for (var bi = 0; bi < pcs.length; bi += 3) { /* three pieces at a time: the phone's storage is faster when it is kept busy */
          await gate();
          var grp = []; for (var gi = bi; gi < Math.min(pcs.length, bi + 3); gi++) grp.push(sendPiece(gi));
          await Promise.all(grp);
        }
        var r3 = await T.stat({ path: nm });
        done = !!(r3 && r3.exists && r3.size === blob.size);
      }
    } catch (e) { if (UI.cancel) throw e; done = false; }
    if (!done) { /* slow way: base64 pieces */
      await T.remove({ path: nm });
      var CH = 8388608;
      for (var o2 = 0; o2 < blob.size; o2 += CH) {
        await gate();
        await T.write({ path: nm, data: await b64(blob.slice(o2, Math.min(blob.size, o2 + CH))), append: o2 > 0 });
        onp(o2 / blob.size);
      }
    }
    var r2 = await T.stat({ path: nm });
    if (!r2 || !r2.exists || r2.size !== blob.size) throw new Error('copy incomplete');
    window.__turboSrc[nm] = { u: url, size: blob.size };
    return nm;
  }

  /* one overlay -> ONE small transparent PNG (only the used rectangle) + where it goes */
  function ckCanvas(x, W, H, ck) {
    var d = x.getImageData(0, 0, W, H), a = d.data, kr = parseInt(ck.col.slice(1, 3), 16), kg = parseInt(ck.col.slice(3, 5), 16), kb = parseInt(ck.col.slice(5, 7), 16), sim = ck.sim * 441, sm = Math.max(1, ck.sm * 441);
    for (var i = 0; i < a.length; i += 4) {
      if (!a[i + 3]) continue;
      var r = a[i], g = a[i + 1], b = a[i + 2], dist = Math.hypot(r - kr, g - kg, b - kb);
      if (dist < sim) a[i + 3] = 0; else if (dist < sim + sm) { a[i + 3] = 255 * (dist - sim) / sm; if (kg > kr && kg > kb && g > Math.max(r, b)) a[i + 1] = Math.max(r, b); }
    }
    x.putImageData(d, 0, 0);
  }
  function drawLayer(o, tt, W, H) {
    var old = t; t = tt; /* index.html draws text with the global time t */
    try {
      var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
      var x = cv.getContext('2d', { willReadFrequently: true }), el = OE[o.id], s = ovState(o, tt), w = W * s.sc;
      x.translate(W * s.x / 100, H * s.y / 100); x.rotate(s.rot * Math.PI / 180);
      if (o.kind === 'emoji' || o.kind === 'text') txDraw(x, o, w, tt);
      else {
        var m = el.querySelector('img,video'), iw = m.naturalWidth || m.videoWidth || m.width || 1, ih = m.naturalHeight || m.videoHeight || m.height || 1;
        var a = (txMoveCv(x, o, tt, w, w * ih / iw), o.ts ? txAnim(o, tt) : null);
        if (a) { x.globalAlpha *= a.op; x.translate(0, a.dy * w); x.scale(a.sc, a.sc); }
        x.drawImage(m, -w / 2, -w * ih / iw / 2, w, w * ih / iw);
        if (o.ck && o.ck.on) { x.setTransform(1, 0, 0, 1, 0, 0); ckCanvas(x, W, H, o.ck); }
      }
      return cv;
    } finally { t = old; }
  }
  function box(cv, W, H) {
    var d = cv.getContext('2d', { willReadFrequently: true }).getImageData(0, 0, W, H), u = new Uint32Array(d.data.buffer), x0 = W, y0 = H, x1 = -1, y1 = -1;
    for (var y = 0; y < H; y++) { var r = y * W, xs = -1, xe = -1; for (var x = 0; x < W; x++) { if (u[r + x] >>> 24) { if (xs < 0) xs = x; xe = x; } } if (xs >= 0) { if (xs < x0) x0 = xs; if (xe > x1) x1 = xe; if (y < y0) y0 = y; y1 = y; } }
    return x1 < 0 ? null : [x0, y0, x1 - x0 + 1, y1 - y0 + 1];
  }

  /* ---------- key-frame compression: a 10 hour overlay becomes a handful of keys instead of millions of samples ---------- */
  function Swing(dim, eps) {
    this.dim = dim; this.eps = eps; this.keys = []; this.n = 0; this.t0 = 0; this.v0 = null; this.lo = null; this.hi = null; this.tp = 0; this.vp = null;
  }
  Swing.prototype.reset = function () { this.lo = new Array(this.dim).fill(-Infinity); this.hi = new Array(this.dim).fill(Infinity); };
  Swing.prototype.emit = function (t, v) { this.keys.push(t); for (var d = 0; d < this.dim; d++) this.keys.push(v[d]); };
  Swing.prototype.push = function (t, v) {
    var d, dt;
    if (this.v0 === null) { this.v0 = v.slice(); this.t0 = t; this.emit(t, v); this.reset(); this.tp = t; this.vp = v.slice(); return; }
    dt = t - this.t0;
    var nlo = [], nhi = [], ok = true;
    for (d = 0; d < this.dim; d++) {
      var a = (v[d] - this.eps[d] - this.v0[d]) / dt, b = (v[d] + this.eps[d] - this.v0[d]) / dt;
      nlo[d] = Math.max(this.lo[d], a); nhi[d] = Math.min(this.hi[d], b);
      if (nlo[d] > nhi[d]) ok = false;
    }
    if (ok) { this.lo = nlo; this.hi = nhi; }
    else { /* close the segment at the previous sample (on the middle line) and start again from there */
      var kv = [], dtp = this.tp - this.t0;
      for (d = 0; d < this.dim; d++) { var sl = (isFinite(this.lo[d]) && isFinite(this.hi[d])) ? (this.lo[d] + this.hi[d]) / 2 : (this.vp[d] - this.v0[d]) / dtp; kv[d] = this.v0[d] + sl * dtp; }
      this.emit(this.tp, kv); this.v0 = kv; this.t0 = this.tp; this.reset();
      dt = t - this.t0;
      for (d = 0; d < this.dim; d++) { this.lo[d] = (v[d] - this.eps[d] - this.v0[d]) / dt; this.hi[d] = (v[d] + this.eps[d] - this.v0[d]) / dt; }
    }
    this.tp = t; this.vp = v.slice();
  };
  Swing.prototype.finish = function () {
    if (this.v0 === null) return this.keys;
    if (this.tp > this.t0) {
      var d, kv = [], dtp = this.tp - this.t0;
      for (d = 0; d < this.dim; d++) { var sl = (isFinite(this.lo[d]) && isFinite(this.hi[d])) ? (this.lo[d] + this.hi[d]) / 2 : (this.vp[d] - this.v0[d]) / dtp; kv[d] = this.v0[d] + sl * dtp; }
      this.emit(this.tp, kv);
    }
    return this.keys;
  };

  /* ---------- moving / fading / popping / sliding pictures: the picture is drawn ONCE, the movement is a sampled table ---------- */
  function isAnim(o) { var ts = o.ts || {}; return !!((o.kf && o.kf.length) || (ts.ai && ts.ai !== 'none') || (ts.ao && ts.ao !== 'none') || ts.mv); }
  function fontOf(o, w) { var ts = o.ts || {}; return (o.kind === 'text' ? '700 ' : '') + (ts.it ? 'italic ' : '') + w + 'px ' + (FONTS[ts.font] || 'sans-serif'); }
  /* The picture can be ANY size (a long scrolling text, a huge sticker): it is cut into tiles of at most 2048×2048, empty tiles are dropped,
     and the phone only decodes the tiles that are on screen at that moment (RAM stays small). */
  async function animBase(o, a, b, W, H) {
    var mid = (a + b) / 2, st = ovState(o, mid), w0 = W * st.sc, isTxt = o.kind === 'emoji' || o.kind === 'text';
    var o2 = Object.assign({}, o, { ts: Object.assign({}, o.ts || {}, { mv: null, ai: 'none', ao: 'none', wd: null }) });
    var sx = document.createElement('canvas').getContext('2d'), Cw, Ch, m = null, eh = 0;
    if (isTxt) { sx.font = fontOf(o, w0); Cw = Math.ceil(sx.measureText(o.val).width + w0 * 2.5); Ch = Math.ceil(w0 * 3.2); }
    else { m = OE[o.id].querySelector('img,video'); var iw = m.naturalWidth || m.videoWidth || m.width || 1, ih = m.naturalHeight || m.videoHeight || m.height || 1; eh = w0 * ih / iw; Cw = Math.ceil(w0 * 1.3); Ch = Math.ceil(eh * 1.3); }
    if (Cw < 1 || Ch < 1) return null;
    if (Cw * Ch > 400e6) throw new Error('overlay picture is far too large (' + Cw + '×' + Ch + ')');
    var TS = 2048, tiles = [], cv = document.createElement('canvas');
    for (var y0 = 0; y0 < Ch; y0 += TS) for (var x0 = 0; x0 < Cw; x0 += TS) {
      var tw = Math.min(TS, Cw - x0), th = Math.min(TS, Ch - y0);
      cv.width = tw; cv.height = th; /* resets the canvas */
      var x = cv.getContext('2d', { willReadFrequently: true });
      x.translate(Cw / 2 - x0, Ch / 2 - y0);
      if (isTxt) txDraw(x, o2, w0, mid); else x.drawImage(m, -w0 / 2, -eh / 2, w0, eh);
      var bx = box(cv, tw, th); if (!bx) continue;
      var c2 = document.createElement('canvas'); c2.width = bx[2]; c2.height = bx[3];
      c2.getContext('2d').drawImage(cv, bx[0], bx[1], bx[2], bx[3], 0, 0, bx[2], bx[3]);
      tiles.push({ canvas: c2, w: bx[2], h: bx[3], ox: Cw / 2 - (x0 + bx[0]), oy: Ch / 2 - (y0 + bx[1]) });
      if (tiles.length % 8 === 0) await sleep(0);
    }
    cv.width = cv.height = 1;
    return tiles.length ? { tiles: tiles, sc0: st.sc } : null;
  }
  /* position / size / turn / opacity at each moment, worked out with the SAME functions the preview uses */
  async function animTable(o, a, b, W, H, sc0) {
    var STEP = 1 / 30, n = Math.ceil((b - a) / STEP) + 1, ts = o.ts || {}, isTxt = o.kind === 'emoji' || o.kind === 'text';
    var hasAnim = (ts.ai && ts.ai !== 'none') || (ts.ao && ts.ao !== 'none');
    var sx = document.createElement('canvas').getContext('2d'), m = null, ar = 1;
    if (!isTxt) { m = OE[o.id].querySelector('img,video'); ar = (m.naturalHeight || m.videoHeight || m.height || 1) / (m.naturalWidth || m.videoWidth || m.width || 1); }
    var sw = new Swing(5, [0.25, 0.25, 0.0004, 0.02, 0.003]);
    for (var i = 0; i < n; i++) {
      if (i % 4000 === 3999) { await gate(); await sleep(0); }
      var tt = Math.min(b, a + i * STEP), st = ovState(o, tt), w = W * st.sc, an = hasAnim ? txAnim(o, tt) : { op: 1, sc: 1, dy: 0 };
      var cx = W * st.x / 100, cy = H * st.y / 100, ag = st.rot * Math.PI / 180, mx = 0, my = 0;
      if (ts.mv) {
        var ew, eh;
        if (isTxt) { sx.font = fontOf(o, w); ew = sx.measureText(o.val).width + .8 * w; eh = 1.4 * w; } else { ew = w; eh = w * ar; }
        var mv = txMove(o, tt - o.from, Math.max(.1, o.to - o.from), W, H, ew, eh, cx, cy);
        if (mv) { mx = mv[0]; my = mv[1]; }
      }
      var dyw = an.dy * w;
      sw.push(tt * 1000, [cx + mx - dyw * Math.sin(ag), cy + my + dyw * Math.cos(ag), (w / (W * sc0)) * an.sc, st.rot, an.op]);
    }
    return { tab: sw.finish().map(function (v, i) { return Math.round(v * 10000) / 10000; }) };
  }

  /* All EXTRA sound (overlay videos + added audio, with volume / fade / equalizer). The files go to the cache and the PHONE decodes and mixes them,
     window by window, into one raw track (native TurboPlugin.buildMix): any length, memory use does not grow. The clip's own sound is handled natively. */
  async function overlayTrack(T, Tt, onp, vidFiles) {
    var sounds = [], any = false;
    var k = 0;
    for (var i = 0; i < ovs.length; i++) {
      var o = ovs[i]; if (o.kind !== 'vid') continue;
      var vol = o.vol == null ? 1 : o.vol; if (vol <= 0.001) continue;
      var a = Math.max(0, o.from), b = Math.min(Tt, o.to); if (b <= a || !vidFiles[o.val]) continue;
      sounds.push({ path: vidFiles[o.val], vol: vol, fi: 0, fo: 0, A: a, B: b, pcs: ovPieces(o, a, b) });
    }
    for (var j = 0; j < aud.length; j++) {
      var u = aud[j], vl = u.vol == null ? 1 : u.vol; if (vl <= 0.001) continue;
      var a2 = Math.max(0, u.from), b2 = Math.min(Tt, u.to); if (b2 <= a2) continue;
      await gate();
      var nm = 'taud' + (k++) + '.dat';
      await copySource(T, await (await fetch(u.url)).blob(), u.url, function (f) { onp(.5 * f); }, nm);
      var fx = u.fx || {};
      sounds.push({ path: nm, vol: vl, fi: u.fi || 0, fo: u.fo || 0, A: u.from, B: u.to, bass: fx.bass || 0, mid: fx.mid || 0, tre: fx.tre || 0, pcs: [{ a: a2, b: b2, s: (u.in || 0) + (a2 - u.from) }] });
    }
    if (!sounds.length) return null;
    await gate();
    var sub = await T.addListener('mixprogress', function (e) { onp(.5 + .5 * (e.p || 0)); });
    try { var r = await T.buildMix({ sounds: sounds }); return (r && r.pieces) ? { path: r.path, startMs: r.startMs } : null; }
    finally { try { sub.remove(); } catch (_) { } }
  }

  /* a speed curve is cut into 40 parts of constant speed (the app's own table: cum[0..200] = timeline seconds after each 1/200 of the clip) */
  function curveParts(c) {
    var N = 40, tab = cvTab(c).cum, parts = [], bad = 0, sd = c.out - c.in;
    for (var j = 0; j < N; j++) {
      var a = c.in + sd * j / N, b = c.in + sd * (j + 1) / N, dt = tab[(j + 1) * 5] - tab[j * 5], sp = dt > 1e-6 ? (b - a) / dt : 0;
      if (!(sp > 0.1 && sp <= 16)) bad = sp || 0.0001;
      parts.push({ a: a, b: b, dt: dt, sp: sp });
    }
    return { parts: parts, bad: bad };
  }

  /* ---------- the clip's LOOK (geometry + colour + opacity), worked out with the SAME formulas the preview / Standard export use ---------- */
  function mm(A, B) { return [A[0] * B[0] + A[2] * B[1], A[1] * B[0] + A[3] * B[1], A[0] * B[2] + A[2] * B[3], A[1] * B[2] + A[3] * B[3], A[0] * B[4] + A[2] * B[5] + A[4], A[1] * B[4] + A[3] * B[5] + A[5]]; }
  function mT(x, y) { return [1, 0, 0, 1, x, y]; }
  function mR(d) { var r = d * Math.PI / 180, c = Math.cos(r), s = Math.sin(r); return [c, s, -s, c, 0, 0]; }
  function mSk(d) { return [1, 0, Math.tan(d * Math.PI / 180), 1, 0, 0]; }
  function mS(k) { return [k, 0, 0, k, 0, 0]; }
  var CI = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0];
  function cm(A, B) { var C = new Array(12); for (var i = 0; i < 3; i++) for (var j = 0; j < 4; j++) { var sum = 0; for (var k = 0; k < 3; k++) sum += A[i * 4 + k] * B[k * 4 + j]; if (j === 3) sum += A[i * 4 + 3]; C[i * 4 + j] = sum; } return C; }
  function cBr(b) { return [b, 0, 0, 0, 0, b, 0, 0, 0, 0, b, 0]; }
  function cCo(c) { var o = .5 * (1 - c); return [c, 0, 0, o, 0, c, 0, o, 0, 0, c, o]; }
  function cSa(s) { return [.213 + .787 * s, .715 - .715 * s, .072 - .072 * s, 0, .213 - .213 * s, .715 + .285 * s, .072 - .072 * s, 0, .213 - .213 * s, .715 - .715 * s, .072 + .928 * s, 0]; }
  function cSe(a) { var k = 1 - a; return [.393 + .607 * k, .769 - .769 * k, .189 - .189 * k, 0, .349 - .349 * k, .686 + .314 * k, .168 - .168 * k, 0, .272 - .272 * k, .534 - .534 * k, .131 + .869 * k, 0]; }
  function cHu(d) { var r = d * Math.PI / 180, c = Math.cos(r), s = Math.sin(r); return [.213 + c * .787 - s * .213, .715 - c * .715 - s * .715, .072 - c * .072 + s * .928, 0, .213 - c * .213 + s * .143, .715 + c * .285 + s * .140, .072 - c * .072 - s * .283, 0, .213 - c * .213 - s * .787, .715 - c * .715 + s * .715, .072 + c * .928 + s * .072, 0]; }
  function r4(v) { return Math.round(v * 10000) / 10000; }

  /* ---------- shader effects: the GLSL lives HERE, so a new look needs no change in the native plugin ---------- */
  var GH = 'precision highp float;\nuniform sampler2D uTexSampler;\nvarying vec2 vTexSamplingCoord;\nuniform vec2 uSize;\n';
  var GLSL = {
    blur: GH + 'uniform vec2 uDir; uniform float uSigma;\nvoid main(){\n float stride = max(1.0, uSigma * 3.0 / 12.0);\n vec4 acc = vec4(0.0); float ws = 0.0;\n for (int i = -12; i <= 12; i++) {\n  float o = float(i) * stride;\n  float w = exp(-0.5 * o * o / (uSigma * uSigma));\n  vec2 uv = clamp(vTexSamplingCoord + uDir * o / uSize, vec2(0.0), vec2(1.0));\n  acc += texture2D(uTexSampler, uv) * w; ws += w;\n }\n gl_FragColor = acc / ws;\n}\n',
    sharp: GH + 'uniform float uA;\nvoid main(){\n vec2 px = 1.0 / uSize;\n vec4 c = texture2D(uTexSampler, vTexSamplingCoord);\n vec4 n = texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(0.0, px.y), 0.0, 1.0));\n vec4 s = texture2D(uTexSampler, clamp(vTexSamplingCoord - vec2(0.0, px.y), 0.0, 1.0));\n vec4 e = texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(px.x, 0.0), 0.0, 1.0));\n vec4 w = texture2D(uTexSampler, clamp(vTexSamplingCoord - vec2(px.x, 0.0), 0.0, 1.0));\n vec4 r = c * (1.0 + 4.0 * uA) - uA * (n + s + e + w);\n gl_FragColor = vec4(clamp(r.rgb, 0.0, 1.0), c.a);\n}\n',
    mosaic: GH + 'uniform float uCell;\nvoid main(){\n vec2 cell = vec2(uCell) / uSize;\n vec2 b = floor(vTexSamplingCoord / cell) * cell;\n vec4 a = texture2D(uTexSampler, min(b + cell * vec2(0.25, 0.25), vec2(1.0))) + texture2D(uTexSampler, min(b + cell * vec2(0.75, 0.25), vec2(1.0))) + texture2D(uTexSampler, min(b + cell * vec2(0.25, 0.75), vec2(1.0))) + texture2D(uTexSampler, min(b + cell * vec2(0.75, 0.75), vec2(1.0)));\n gl_FragColor = a * 0.25;\n}\n',
    lut: GH + 'uniform sampler2D uTex2; uniform float uN; uniform float uK;\nvoid main(){\n vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;\n float n = uN; float b = c.b * (n - 1.0); float b0 = floor(b); float b1 = min(b0 + 1.0, n - 1.0); float f = b - b0;\n float y = (c.g * (n - 1.0) + 0.5) / n; float x = c.r * (n - 1.0) + 0.5;\n vec3 a = texture2D(uTex2, vec2((b0 * n + x) / (n * n), y)).rgb;\n vec3 d = texture2D(uTex2, vec2((b1 * n + x) / (n * n), y)).rgb;\n gl_FragColor = vec4(mix(c, mix(a, d, f), uK), 1.0);\n}\n',
    curves: GH + 'uniform sampler2D uTex2;\nfloat cv(float x, float row){ x = clamp(x, 0.0, 1.0); return texture2D(uTex2, vec2((x * 16.0 + 0.5) / 17.0, (row + 0.5) / 3.0)).r; }\nvoid main(){\n vec4 c = texture2D(uTexSampler, vTexSamplingCoord);\n gl_FragColor = vec4(cv(c.r, 0.0), cv(c.g, 1.0), cv(c.b, 2.0), c.a);\n}\n',
    mask: GH + 'uniform sampler2D uTex2;\nvoid main(){\n vec4 c = texture2D(uTexSampler, vTexSamplingCoord);\n float m = texture2D(uTex2, vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y)).a;\n gl_FragColor = vec4(c.rgb * m, 1.0);\n}\n'
  };
  var BMN = { 'screen': 1, 'multiply': 2, 'overlay': 3, 'soft-light': 4, 'lighten': 5, 'darken': 6, 'difference': 7, 'plus-lighter': 8 };
  GLSL.blend = 'precision highp float;\nuniform sampler2D uTexSampler;\nuniform sampler2D uTex2;\nvarying vec2 vTexSamplingCoord;\nuniform float uMode;\n' +
    'float sl(float cb, float cs){ if (cs <= 0.5) return cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb); float d = cb <= 0.25 ? ((16.0 * cb - 12.0) * cb + 4.0) * cb : sqrt(cb); return cb + (2.0 * cs - 1.0) * (d - cb); }\n' +
    'float ov(float cb, float cs){ return cb <= 0.5 ? cs * 2.0 * cb : cs + (2.0 * cb - 1.0) - cs * (2.0 * cb - 1.0); }\n' +
    'vec3 bl(vec3 cb, vec3 cs, float m){\n if (m < 1.5) return cb + cs - cb * cs;\n if (m < 2.5) return cb * cs;\n if (m < 3.5) return vec3(ov(cb.r, cs.r), ov(cb.g, cs.g), ov(cb.b, cs.b));\n if (m < 4.5) return vec3(sl(cb.r, cs.r), sl(cb.g, cs.g), sl(cb.b, cs.b));\n if (m < 5.5) return max(cb, cs);\n if (m < 6.5) return min(cb, cs);\n if (m < 7.5) return abs(cb - cs);\n return min(cb + cs, vec3(1.0));\n}\n' +
    'void main(){\n vec4 b = texture2D(uTexSampler, vTexSamplingCoord);\n vec4 s = texture2D(uTex2, vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y));\n vec3 cs = s.a > 0.001 ? s.rgb / s.a : vec3(0.0);\n gl_FragColor = vec4(mix(b.rgb, bl(b.rgb, cs, uMode), s.a), b.a);\n}\n';
  GLSL.bgColor = GH + 'uniform vec4 uRect; uniform vec3 uBg;\nvoid main(){\n vec2 p = vTexSamplingCoord;\n bool inside = p.x >= uRect.x - 0.0005 && p.x <= uRect.z + 0.0005 && p.y >= uRect.y - 0.0005 && p.y <= uRect.w + 0.0005;\n vec4 c = texture2D(uTexSampler, p);\n gl_FragColor = inside ? vec4(c.rgb, 1.0) : vec4(uBg, 1.0);\n}\n';
  GLSL.bgFill = GH + 'uniform vec4 uRect; uniform float uS;\nvoid main(){\n vec2 p = vTexSamplingCoord;\n bool inside = p.x >= uRect.x - 0.0005 && p.x <= uRect.z + 0.0005 && p.y >= uRect.y - 0.0005 && p.y <= uRect.w + 0.0005;\n vec2 q = clamp(vec2(0.5) + (p - vec2(0.5)) / uS, uRect.xy, uRect.zw);\n gl_FragColor = inside ? vec4(texture2D(uTexSampler, p).rgb, 1.0) : vec4(texture2D(uTexSampler, q).rgb, 1.0);\n}\n';
  GLSL.bgBlur = GH + 'uniform vec4 uRect; uniform vec2 uDir; uniform float uSigma;\nvoid main(){\n vec2 p = vTexSamplingCoord;\n bool inside = p.x >= uRect.x - 0.0005 && p.x <= uRect.z + 0.0005 && p.y >= uRect.y - 0.0005 && p.y <= uRect.w + 0.0005;\n if (inside) { gl_FragColor = texture2D(uTexSampler, p); return; }\n float stride = max(1.0, uSigma * 3.0 / 12.0); vec4 acc = vec4(0.0); float ws = 0.0;\n for (int i = -12; i <= 12; i++) { float o = float(i) * stride; float w = exp(-0.5 * o * o / (uSigma * uSigma)); vec2 uv = clamp(p + uDir * o / uSize, vec2(0.0), vec2(1.0)); acc += texture2D(uTexSampler, uv) * w; ws += w; }\n gl_FragColor = acc / ws;\n}\n';
  var DIMS = {};
  async function vidDims(url) {
    if (DIMS[url]) return DIMS[url];
    var d = await new Promise(function (res) { var el = document.createElement('video'); el.preload = 'metadata'; el.muted = true; var done = function (v) { try { el.removeAttribute('src'); } catch (_) { } res(v); }; el.onloadedmetadata = function () { done({ w: el.videoWidth, h: el.videoHeight }); }; el.onerror = function () { done(null); }; setTimeout(function () { done(null); }, 5000); el.src = url; });
    return (DIMS[url] = d || { w: 16, h: 9 });
  }
  async function pngFile(T, cv, name, tmp) {
    var b = await new Promise(function (r) { cv.toBlob(r, 'image/png'); });
    await T.write({ path: name, data: await b64(b), append: false }); tmp.push(name); return name;
  }
  /* the shader effects of one clip: pre = before the colour matrix, post = after it (blur, curves, sharpen, motion blur, mask) */
  async function clipFx(T, c, ci, W, H, tmp) {
    var fx = c.fx || {}, pre = [], post = [], rt = ((c.rot || 0) % 360 + 360) % 360, bw = (rt % 180) ? H : W;
    if (bgm && (bgm.t === 'color' || bgm.t === 'blur')) { /* the bars around the picture: a colour, or a blurred, enlarged copy of the picture */
      var vd = await vidDims(c.url), vw = (rt % 180) ? vd.h : vd.w, vh = (rt % 180) ? vd.w : vd.h, kk = Math.min(W / vw, H / vh), rw = vw * kk / W, rh = vh * kk / H, rc = [(1 - rw) / 2, (1 - rh) / 2, (1 + rw) / 2, (1 + rh) / 2];
      if (rw < 0.999 || rh < 0.999) {
        if (bgm.t === 'color') { var hx = String(bgm.c || '#000000'); pre.push({ frag: GLSL.bgColor, f: { uRect: rc, uBg: [parseInt(hx.slice(1, 3), 16) / 255, parseInt(hx.slice(3, 5), 16) / 255, parseInt(hx.slice(5, 7), 16) / 255] } }); }
        else {
          var sg2 = (bgm.b || 22) * W / Math.max(1, fr.clientWidth), cover = Math.max(W / vw, H / vh) * 1.25 / kk;
          pre.push({ frag: GLSL.bgFill, f: { uRect: rc, uS: cover } });
          pre.push({ frag: GLSL.bgBlur, f: { uRect: rc, uDir: [1, 0], uSigma: sg2 } });
          pre.push({ frag: GLSL.bgBlur, f: { uRect: rc, uDir: [0, 1], uSigma: sg2 } });
        }
      }
    }
    if (fx.mos > 0) pre.push({ frag: GLSL.mosaic, f: { uCell: mosCell(fx, bw) } });
    else if (c.lut) {
      var e = null; for (var k = 0; k < 80 && !(e = lutLoad(c.lut.u)); k++) await sleep(100);
      if (!e) throw new Error('the LUT file could not be read');
      var lc = document.createElement('canvas'); lc.width = e.n * e.n; lc.height = e.n;
      lc.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(e.d), e.n * e.n, e.n), 0, 0);
      pre.push({ frag: GLSL.lut, f: { uN: e.n, uK: c.lut.i == null ? 1 : c.lut.i }, tex: await pngFile(T, lc, 'tfxl' + ci + '.png', tmp) });
    }
    if (fx.blur > 0) { post.push({ frag: GLSL.blur, f: { uDir: [1, 0], uSigma: fx.blur } }); post.push({ frag: GLSL.blur, f: { uDir: [0, 1], uSigma: fx.blur } }); }
    if (c.cg) {
      var cc = document.createElement('canvas'); cc.width = 17; cc.height = 3; var cx = cc.getContext('2d');
      ['r', 'g', 'b'].forEach(function (ch, row) { cgTable(c.cg, ch).split(' ').forEach(function (v, x) { var q = Math.round(255 * parseFloat(v)); cx.fillStyle = 'rgb(' + q + ',' + q + ',' + q + ')'; cx.fillRect(x, row, 1, 1); }); });
      post.push({ frag: GLSL.curves, tex: await pngFile(T, cc, 'tfxc' + ci + '.png', tmp) });
    }
    if (c.sharp) post.push({ frag: GLSL.sharp, f: { uA: [.3, .6, 1][Math.max(0, Math.min(2, c.sharp - 1))] } });
    if (c.mb) { var mk = String(c.mb), sg = { 1: 3, 2: 7, 3: 14 }[mk.charAt(1)] || 3; post.push({ frag: GLSL.blur, f: { uDir: mk.charAt(0) === 'h' ? [1, 0] : [0, 1], uSigma: sg } }); }
    if (c.mk) { /* the mask picture is drawn like the Standard export draws it, then turned / mirrored like the clip */
      var m = c.mk, g = mkG(m, bw, bw === W ? H : W), mc = document.createElement('canvas'); mc.width = W; mc.height = H;
      var B = mc.getContext('2d'); B.translate(W / 2, H / 2); B.rotate(rt * Math.PI / 180); B.scale(c.flip ? -1 : 1, 1); B.translate(-bw / 2, -(bw === W ? H : W) / 2);
      var bh = bw === W ? H : W;
      B.fillStyle = '#fff'; if (m.inv) B.fillRect(0, 0, bw, bh);
      B.save(); B.filter = g.bl > .3 ? 'blur(' + g.bl + 'px)' : 'none'; B.globalCompositeOperation = m.inv ? 'destination-out' : 'source-over';
      B.translate(g.cx, g.cy); B.rotate(g.r * Math.PI / 180); B.fill(new Path2D(g.d)); B.restore();
      post.push({ frag: GLSL.mask, tex: await pngFile(T, mc, 'tfxm' + ci + '.png', tmp) });
    }
    return { pre: pre, post: post };
  }

  async function buildLooks(T, W, H, Tt) {
    var specs = [], keysT = [], keysR = [], extra = [], anyLook = false, st = 0, tmp = [], fxs = [], nKeys = 0;
    for (var ci = 0; ci < clips.length; ci++) {
      await gate();
      var c = clips[ci], ln = len(c), tr = c.tr, fx = c.fx || {}, f = c.f || PRESETS.none;
      var trOn = !!(tr && tr.type && tr.type !== 'none'), jit = fx.shake > 0 || fx.glit > 0, fadeOn = !!(window.FADE && (FADE.i || FADE.o));
      var dyn = !!(c.kb || (c.kf && c.kf.length)) || jit || trOn || fadeOn, STEP = 1 / 30, n = dyn ? Math.max(2, Math.ceil(ln / STEP) + 1) : 1;
      var swL = new Swing(18, [0.0006, 0.0006, 0.0006, 0.0006, 0.08, 0.08, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002, 0.002]), gid = true, cid = true, jx = 0, jy = 0, jr = 0, jk = 0, jh = 0, js = 1, hold = -1, old = t, flip = c.flip ? -1 : 1;
      try {
        for (var i = 0; i < n; i++) {
          if (i % 3000 === 2999) { await gate(); await sleep(0); }
          var tt = n === 1 ? st + ln / 2 : Math.min(st + ln - 1e-4, st + i * STEP);
          t = tt; /* index.html's own helpers read the global time t */
          var tf = trOn ? trFxAt() : { a: 1, x: 0, s: 1, w: 1 }, kb = KB(c), af = angFill(c);
          if (jit) {
            if (tt >= hold) { /* same random shake / glitch as the preview, new values every 50 ms */
              var rd = Math.random; jx = 0; jy = 0; jr = 0; jk = 0; jh = 0; js = 1;
              if (fx.shake > 0) { jx = (rd() * 2 - 1) * 2.2 * fx.shake; jy = (rd() * 2 - 1) * 2.2 * fx.shake; jr = (rd() * 2 - 1) * 1.2 * fx.shake; js = 1 + .06 * fx.shake; }
              if (fx.glit > 0 && rd() < .1 + .25 * fx.glit) { jx += (rd() * 2 - 1) * 7 * fx.glit; jk = (rd() * 2 - 1) * 8 * fx.glit; jh = (rd() < .5 ? 1 : -1) * (40 + rd() * 80) * fx.glit; js = Math.max(js, 1.05); }
              hold = tt + .05;
            }
          }
          var g = mT(tf.x / 100 * W, 0);
          g = mm(g, mT(jx / 100 * W, jy / 100 * H)); g = mm(g, mR(jr)); g = mm(g, mSk(jk)); g = mm(g, mS(js)); g = mm(g, mS(tf.s));
          g = mm(g, mT(((c.zx || 0) + kb.x) / 100 * W, ((c.zy || 0) + kb.y) / 100 * H)); g = mm(g, mS((c.zm || 1) * kb.m));
          g = mm(g, mR(c.ang || 0)); g = mm(g, mS(af));
          var col = CI;
          col = cm(cBr(f.b), col); col = cm(cCo(f.c), col); col = cm(cSa(f.s), col);
          if (f.sep > 0) col = cm(cSe(f.sep), col);
          if (f.hue) col = cm(cHu(f.hue), col);
          if (jh) col = cm(cHu(jh), col);
          if (c.cg) { var cg = c.cg; col = cm([1 + .25 * cg.temp + .1 * cg.tint, 0, 0, 0, 0, 1 - .2 * cg.tint, 0, 0, 0, 0, 1 - .25 * cg.temp + .1 * cg.tint, 0], col); col = cm(cSa(cg.sat), col); }
          var al = tf.a;
          if (fadeOn) { var fi = FADE.i || 0, fo = FADE.o || 0; al *= Math.max(0, Math.min(1, (fi > 0 && tt < fi) ? tt / fi : 1, (fo > 0 && tt > Tt - fo) ? (Tt - tt) / fo : 1)); }
          if (Math.abs(al - 1) > 1e-4) col = cm([al, 0, 0, 0, 0, al, 0, 0, 0, 0, al, 0], col);
          for (var q = 0; q < 6; q++) g[q] = r4(g[q]);
          for (var q2 = 0; q2 < 12; q2++) col[q2] = r4(col[q2]);
          if (Math.abs(g[0] - 1) > 1e-3 || Math.abs(g[3] - 1) > 1e-3 || Math.abs(g[1]) > 1e-3 || Math.abs(g[2]) > 1e-3 || Math.abs(g[4]) > .02 || Math.abs(g[5]) > .02) gid = false;
          for (var q3 = 0; q3 < 12; q3++) if (Math.abs(col[q3] - CI[q3]) > 1e-3) cid = false;
          swL.push(tt * 1000, g.concat(col));
        }
        /* wipe transition: a black cover from the right edge, worked out per frame */
        if (tr && tr.type === 'wipe') {
          var wv = [], wn = Math.max(2, Math.ceil(Math.min(tr.dur, ln) * 60) + 1);
          for (var wi = 0; wi < wn; wi++) { t = Math.min(st + ln - 1e-4, st + wi / 60); wv.push(r4(trFxAt().w)); }
          extra.push({ type: 'wipe', fromMs: Math.round(st * 1000), toMs: Math.round((st + Math.min(tr.dur, ln)) * 1000), t0Ms: st * 1000, stepMs: 1000 / 60, w: wv });
          anyLook = true;
        }
      } finally { t = old; }
      /* vignette / film grain: one still picture over the clip (below the overlays) */
      if (fx.vig > 0 || fx.grn > 0) {
        var vc = document.createElement('canvas'); vc.width = W; vc.height = H; var vx = vc.getContext('2d');
        if (fx.vig > 0) { var gr = vx.createRadialGradient(W / 2, H / 2, Math.min(W, H) * .25, W / 2, H / 2, Math.max(W, H) * .75); gr.addColorStop(0, 'rgba(0,0,0,0)'); gr.addColorStop(1, 'rgba(0,0,0,' + fx.vig + ')'); vx.fillStyle = gr; vx.fillRect(0, 0, W, H); }
        if (fx.grn > 0) { vx.save(); vx.globalAlpha = fx.grn * .5; vx.fillStyle = vx.createPattern(NOISE, 'repeat'); vx.fillRect(0, 0, W, H); vx.restore(); }
        var vpng = await new Promise(function (r) { vc.toBlob(r, 'image/png'); }), vpath = 'tfx' + ci + '.png';
        await T.write({ path: vpath, data: await b64(vpng), append: false });
        extra.push({ type: 'pic', path: vpath, x: 0, y: 0, fromMs: Math.round(st * 1000), toMs: Math.round((st + ln) * 1000) }); anyLook = true; vc.width = vc.height = 1;
      }
      var cf = await clipFx(T, c, ci, W, H, tmp); fxs.push(cf);
      if (cf.pre.length || cf.post.length) anyLook = true;
      if (!gid || !cid) anyLook = true;
      var kk = swL.finish(), kn = kk.length / 19;
      specs.push({ geomId: gid, colId: cid, n: (gid && cid) ? 0 : kn, off: nKeys });
      if (!(gid && cid)) { for (var z = 0; z < kn; z++) { keysT.push(Math.round(kk[z * 19] * 10) / 10); for (var z2 = 1; z2 < 19; z2++) keysR.push(Math.round(kk[z * 19 + z2] * 10000) / 10000); } nKeys += kn; }
      st += ln;
    }
    var file = null;
    if (anyLook && keysT.length) { /* the (small) tables travel as a file, not through the bridge call */
      var blob = new Blob([JSON.stringify({ t: keysT, r: keysR })]), CH = 6291456;
      await T.remove({ path: 'tlooks.json' });
      for (var o3 = 0; o3 < blob.size; o3 += CH) await T.write({ path: 'tlooks.json', data: await b64(blob.slice(o3, Math.min(blob.size, o3 + CH))), append: o3 > 0 });
      file = 'tlooks.json';
    }
    return { specs: specs, extra: extra, file: file, any: anyLook, fxs: fxs, tmp: tmp };
  }

  /* ---------- 2) the Turbo run ---------- */
  async function runTurbo(S, F, W, H, br) {
    var T = plug().T;
    var Tt = total(), c = clips[0], inMs = Math.round(c.in * 1000), outMs = Math.round(c.out * 1000);
    UI.cancel = false; UI.paused = false; ui();
    var pb = btn('⏸ Pause', async function () {
      try {
        if (!UI.paused) { UI.paused = true; pb.textContent = '▶ Resume'; try { await T.pause(); } catch (_) { } status('⏸ ' + pct()); var e0 = $q('#tbe'); if (e0) e0.textContent = 'Paused'; if (pzRef) { pzRef.on = true; pzRef.at = performance.now(); } }
        else { UI.paused = false; pb.textContent = '⏸ Pause'; if (pzRef && pzRef.on) { pzRef.ms += performance.now() - pzRef.at; pzRef.on = false; } try { await T.resume(); } catch (_) { } status('🚀 ' + pct()); }
      } catch (_) { }
    }, true);
    actions([pb, btn('✕', function () { UI.cancel = true; UI.paused = false; try { T.cancel(); } catch (_) { } }, false)]);
    var PR = { max: 0 }, bump = function (v) { PR.max = Math.max(PR.max, Math.min(1, v)); setP(PR.max); };
    var pct = function () { return Math.round(PR.max * 100) + '%'; };
    info(W + '×' + H + ' · ' + F + ' fps · ' + X('b') + ' · L2');
    var cp = performance.now(), ovMs = 0, sub = null, r0 = 0, pzRef = { on: false, at: 0, ms: 0 };
    try {
      /* a) source copy */
      status('📥 ' + pct());
      await gate();
      var blob = await (await fetch(c.url)).blob();
      var src = await copySource(T, blob, c.url, function (f) { bump(.12 * f); status('📥 ' + pct()); });
      var srcNames = {}; srcNames[c.url] = src;
      for (var ck = 1; ck < clips.length; ck++) { /* the other clips: each different video file is copied once */
        var cu = clips[ck].url; if (srcNames[cu]) continue;
        await gate();
        srcNames[cu] = await copySource(T, await (await fetch(cu)).blob(), cu, function (f) { bump(.12 * f); status('📥 ' + pct()); }, 'tsrc' + (Object.keys(srcNames).length) + '.mp4');
      }
      cp = performance.now() - cp;
      /* the output bitrate never goes (much) above the source's own: a higher one only makes the file bigger */
      try { if (blob.size > 0 && c.dur > 0) { var sv = blob.size * 8 / c.dur * 0.93, cap = Math.max(1.0e6, Math.round(sv * 1.4)); if (br > cap) br = cap; } } catch (_) { }
      /* b) overlays: each one is drawn ONCE (a still picture) / overlay video is decoded once, in order */
      var t1 = performance.now(), recs = [], vk = 0, vidFiles = {}, tm = { vcopy: 0, png: 0, sound: 0, looks: 0 };
      var tv0 = performance.now();
      for (var iv = 0; iv < ovs.length; iv++) { /* overlay VIDEO files first (the sound builder needs them too) */
        var ov = ovs[iv], a0 = Math.max(0, ov.from), b0 = Math.min(Tt, ov.to);
        if (ov.kind !== 'vid' || b0 <= a0 || vidFiles[ov.val]) continue;
        await gate();
        var vnm0 = 'tovv' + (vk++) + '.mp4';
        await copySource(T, await (await fetch(ov.val)).blob(), ov.val, function (f) { bump(.12 + .04 * f); status('📥 ' + pct()); }, vnm0);
        vidFiles[ov.val] = vnm0;
      }
      tm.vcopy = performance.now() - tv0;
      /* the sound is built by the phone (its own thread) WHILE the pictures below are prepared here */
      var ts0 = performance.now();
      var soundP = overlayTrack(T, Tt, function (f) { bump(.16 + .03 * f); status('🔊 ' + pct()); }, vidFiles).then(function (r) { tm.sound = performance.now() - ts0; return r; });
      soundP.catch(function () { });
      var tp0 = performance.now();
      for (var i = 0; i < ovs.length; i++) {
        await gate();
        var o = ovs[i], a = Math.max(0, o.from), b = Math.min(Tt, o.to);
        if (b <= a) continue;
        var vd = (o.kind === 'vid' && OE[o.id]) ? OE[o.id].querySelector('video') : null;
        if (vd) {
          if (vd.readyState < 1) await Promise.race([new Promise(function (r) { vd.addEventListener('loadedmetadata', r, { once: true }); }), sleep(4000)]);
          if (!vd.videoWidth) throw new Error('overlay video size unknown');
          var vnm = vidFiles[o.val];
          var s0 = ovState(o, (a + b) / 2), pcs = ovPieces(o, a, b); if (!pcs.length) continue; var vtb = isAnim(o) ? await animTable(o, a, b, W, H, s0.sc) : null;
          recs.push({ type: 'vid', path: vnm, fromMs: Math.round(pcs[0].a * 1000), toMs: Math.round(pcs[pcs.length - 1].b * 1000), pcs: pcs.map(function (p) { return [Math.round(p.a * 1000), Math.round(p.b * 1000), Math.round(p.s * 1000)]; }), durMs: Math.round((o.dur || 0) * 1000), x: s0.x, y: s0.y, sc: s0.sc, rot: s0.rot, ar: vd.videoHeight / vd.videoWidth, tab: vtb ? vtb.tab : null, bm: BMN[o.bm] || 0, ck: (o.ck && o.ck.on) ? { col: o.ck.col, sim: o.ck.sim, sm: o.ck.sm } : null });
          continue;
        }
        var tsx = o.ts || {};
        if ((tsx.ai === 'type' || tsx.wd) && (o.kind === 'text' || o.kind === 'emoji')) { /* the text changes: one picture for every state, shown during its own time */
          var keyAt = function (tq) { return tsx.wd ? txWords(o, tq).idx : Math.ceil(String(o.val).length * txAnim(o, tq).n); };
          var segs = [], s0 = a, k0 = keyAt(a), STP = 1 / 60;
          for (var tq = a + STP; tq < b; tq += STP) { var kq = keyAt(tq); if (kq !== k0) { segs.push([s0, tq]); s0 = tq; k0 = kq; } }
          segs.push([s0, b]);
          for (var sg = 0; sg < segs.length; sg++) {
            await gate();
            var cvt = drawLayer(o, (segs[sg][0] + segs[sg][1]) / 2, W, H), bxt = box(cvt, W, H);
            if (!bxt) continue;
            var c3 = document.createElement('canvas'); c3.width = bxt[2]; c3.height = bxt[3];
            c3.getContext('2d').drawImage(cvt, bxt[0], bxt[1], bxt[2], bxt[3], 0, 0, bxt[2], bxt[3]);
            var pt = await new Promise(function (r) { c3.toBlob(r, 'image/png'); }), ppt = 'tov' + i + '_s' + sg + '.png';
            await T.write({ path: ppt, data: await b64(pt), append: false });
            recs.push({ type: 'pic', path: ppt, x: bxt[0], y: bxt[1], fromMs: Math.round(segs[sg][0] * 1000), toMs: Math.round(segs[sg][1] * 1000), bm: BMN[o.bm] || 0 });
          }
          bump(.12 + .08 * (i + 1) / ovs.length); status('🖼 ' + pct()); continue;
        }
        if (isAnim(o)) { /* moving picture: base picture (in tiles) once + sampled movement table */
          var ab = await animBase(o, a, b, W, H); if (!ab) continue;
          var tl = [];
          for (var q = 0; q < ab.tiles.length; q++) {
            var tq = ab.tiles[q], apng = await new Promise(function (r) { tq.canvas.toBlob(r, 'image/png'); }), apath = 'tov' + i + '_' + q + '.png';
            await T.write({ path: apath, data: await b64(apng), append: false });
            tl.push({ path: apath, ox: tq.ox, oy: tq.oy, w: tq.w, h: tq.h }); tq.canvas.width = tq.canvas.height = 1;
          }
          var tb = await animTable(o, a, b, W, H, ab.sc0);
          recs.push({ type: 'anim', tiles: tl, fromMs: Math.round(a * 1000), toMs: Math.round(b * 1000), tab: tb.tab, bm: BMN[o.bm] || 0 });
          bump(.12 + .08 * (i + 1) / ovs.length); status('🖼 ' + pct()); continue;
        }
        var cv = drawLayer(o, (a + b) / 2, W, H), bx = box(cv, W, H);
        if (!bx) continue;
        var c2 = document.createElement('canvas'); c2.width = bx[2]; c2.height = bx[3];
        c2.getContext('2d').drawImage(cv, bx[0], bx[1], bx[2], bx[3], 0, 0, bx[2], bx[3]);
        var png = await new Promise(function (r) { c2.toBlob(r, 'image/png'); });
        var path = 'tov' + i + '.png';
        await T.write({ path: path, data: await b64(png), append: false });
        recs.push({ type: 'pic', path: path, x: bx[0], y: bx[1], fromMs: Math.round(a * 1000), toMs: Math.round(b * 1000), bm: BMN[o.bm] || 0 });
        bump(.12 + .08 * (i + 1) / ovs.length); status('🖼 ' + pct());
      }
      var mix = await soundP;
      status('🎚 ' + pct()); var tl0 = performance.now();
      var LK = await buildLooks(T, W, H, Tt); tm.looks = performance.now() - tl0;
      var multi = clips.length > 1 || LK.any || recs.some(function (r) { return r.bm; }) || clips.some(function (cc) { return !!cc.cv || (cc.speed == null ? 1 : cc.speed) !== 1; });
      if (LK.extra.length) recs = LK.extra.concat(recs); /* below the overlays */
      var clipSpecs = null, stt = 0;
      if (multi) {
        clipSpecs = []; var stt2 = 0;
        clips.forEach(function (cc, ix) {
          var sp0 = LK.specs[ix], lk = sp0.n > 0 ? { off: sp0.off, n: sp0.n, geomId: sp0.geomId, colId: sp0.colId } : null;
          var base = { volume: cc.vol == null ? 1 : cc.vol, rot: ((cc.rot || 0) % 360 + 360) % 360, flip: cc.flip ? 1 : 0, look: lk, src: srcNames[cc.url], fxPre: LK.fxs[ix].pre, fxPost: LK.fxs[ix].post };
          if (cc.cv) { /* every constant-speed part is its own clip entry */
            curveParts(cc).parts.forEach(function (pt) {
              clipSpecs.push(Object.assign({}, base, { inMs: Math.round(pt.a * 1000), outMs: Math.round(pt.b * 1000), speed: pt.sp, startMs: Math.round(stt2 * 1000), lenMs: Math.round(pt.dt * 1000) }));
              stt2 += pt.dt;
            });
          } else {
            var ln2 = len(cc);
            clipSpecs.push(Object.assign({}, base, { inMs: Math.round(cc.in * 1000), outMs: Math.round(cc.out * 1000), speed: cc.speed == null ? 1 : cc.speed, startMs: Math.round(stt2 * 1000), lenMs: Math.round(ln2 * 1000) }));
            stt2 += ln2;
          }
        });
      }
      ovMs = performance.now() - t1;
      /* c) GPU render: live progress, speed and the phase the phone is in */
      sub = await T.addListener('progress', function (e) {
        if (UI.paused) return;
        bump(.2 + .8 * (e.p || 0) / 100); status('🚀 ' + pct());
        var q = (e.p || 0) / 100, x = $q('#tbe');
        if (q > .004 && r0) { var el = (performance.now() - r0 - (pzRef ? pzRef.ms : 0)) / 1000; if (x) x.textContent = fmtT(el * (1 - q) / q) + ' left'; info(W + '×' + H + ' · ' + F + ' fps · ' + X('b') + ' · render ' + (Tt * q / el).toFixed(1) + '× real-time'); }
      });
      bump(.2); status('🚀 ' + pct());
      r0 = performance.now();
      /* the native side starts un-paused: if the person paused earlier, pause it again right away */
      if (UI.paused) setTimeout(function () { try { T.pause(); } catch (_) { } }, 400);
      var phIv = setInterval(async function () { try { if (UI.paused) return; var ps = await T.status(); var e1 = $q('#tbe'); if (e1 && ps && ps.phase && ps.state === 'running' && !/left$/.test(e1.textContent)) e1.textContent = ps.phase; } catch (_) { } }, 1500);
      var nm0 = 'OnellyEdit_Turbo_' + S + 'p_' + new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19) + '.mp4';
      var res = await T.render({ finalName: nm0,
        src: src, out: 'turbo_out.mp4', width: W, height: H, bitrate: br, fps: F, rot: ((c.rot || 0) % 360 + 360) % 360, flip: c.flip ? 1 : 0, inMs: inMs, outMs: outMs, volume: c.vol == null ? 1 : c.vol, mix: mix,
        filter: c.f ? { b: c.f.b, c: c.f.c, s: c.f.s, hue: c.f.hue } : null, overlays: recs, clips: clipSpecs, looks: LK.file, swDecode: window.__turboSw === true, blend: recs.some(function (r) { return r.bm; }) ? { frag: GLSL.blend } : null, fade: (window.FADE && (FADE.i || FADE.o)) ? { i: FADE.i || 0, o: FADE.o || 0, total: Tt } : null
      });
      clearInterval(phIv);
      var rend = performance.now() - r0 - (pzRef ? pzRef.ms : 0);
      /* safety net: if overlays exist but the GPU never drew them, this video is wrong -> refuse it */
      var nVid = recs.filter(function (r) { return r.type === 'vid'; }).length, nPic = recs.length - nVid;
      if (nPic) {
        var dg = String(res.ovDiag || '').split(' ').filter(Boolean);
        if (!dg.length || dg.every(function (x) { return !(parseInt((x.split('/')[1] || '0'), 10) > 0); })) throw new Error('overlays were not drawn (calls/hits ' + String(res.ovDiag).slice(0, 60) + ')');
      }
      if (mix && !(res.mixFrames > 0)) throw new Error('overlay sound was not mixed');
      if (nVid && !(res.vidShown > 0)) throw new Error('overlay video was not drawn (frames ' + res.vidShown + ')');
      /* d) keep inside the app */
      var kept = res.kept ? { abs: res.abs } : await T.keep({ path: res.path, name: nm0 }); /* a long video is written straight into its final place */
      setP(1); $q('#tbp').textContent = '✓'; status('✅ Done'); var e2 = $q('#tbe'); if (e2) e2.textContent = '';
      info(res.sizeMB + ' MB · ' + (res.enc ? 'enc ' + res.enc + ' · ' : '') + X('b') + ' L2 · copy ' + secs(cp) + ' · overlays ' + secs(ovMs) + ' · render ' + secs(rend) + ' (' + (Tt / (rend / 1000)).toFixed(1) + '× real-time)' + (res.ovDiag ? ' · ovl calls/hits ' + res.ovDiag : '') + (res.par ? ' · par: ' + res.par : '') + (res.prof ? ' · ms: ' + res.prof : '') + (' · prep: video-copy ' + secs(tm.vcopy) + ' pictures ' + secs(tm.png) + ' sound ' + secs(tm.sound) + ' (parallel) looks ' + secs(tm.looks) + ' · bridge ' + (res.par ? secs(Math.max(0, rend - (parseInt((/(\d+)s$/.exec(res.par) || [0, 0])[1], 10) || 0) * 1000)) : '-')) + (res.vstat ? ' · ov-video look: ' + res.vstat : '') + (res.vdec != null && nVid ? ' · ov-decode ' + secs(res.vdec) + ' convert ' + secs(res.vconv) + ' sw×' + res.vsw : '') + (mix ? ' · ov-sound frames ' + res.mixFrames : '') + (nVid ? ' · ov-video frames ' + res.vidShown + ' (' + secs(res.vidMs) + ')' : ''));
      showDone(T, kept, nm0);
    } finally {
      try { if (typeof phIv !== 'undefined') clearInterval(phIv); } catch (_) { }
      try { if (sub) sub.remove(); } catch (_) { }
      try { await T.clearStatus(); } catch (_) { } /* the result was handed over: nothing left to recover */
      try { var tl2 = (typeof LK !== 'undefined' && LK && LK.tmp) || []; for (var tz = 0; tz < tl2.length; tz++) await T.remove({ path: tl2[tz] }); } catch (_) { }
      try { await T.remove({ path: 'turbo_out.mp4' }); await T.remove({ path: 'tmix.pcm' }); await T.remove({ path: 'tlooks.json' }); } catch (_) { }
      try { /* temporary overlay pictures are not kept */
        var rr = (typeof recs !== 'undefined' && recs) || [];
        for (var z = 0; z < rr.length; z++) { if (rr[z].type === 'pic') await T.remove({ path: rr[z].path }); (rr[z].tiles || []).forEach(function (t2) { T.remove({ path: t2.path }); }); }
      } catch (_) { }
    }
  }
  function fmtT(s) { s = Math.max(0, s); var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return (h ? h + ':' + String(m).padStart(2, '0') : String(m).padStart(2, '0')) + ':' + String(Math.floor(s % 60)).padStart(2, '0'); }

  /* the Done screen: Play / Save to phone / Close */
  function showDone(T, kept, nm0) {
    actions([
      btn('▶ Play', function () { var v = $q('#tbv'); v.style.display = 'block'; v.src = C.convertFileSrc ? C.convertFileSrc(kept.abs) : 'file://' + kept.abs; v.play().catch(function () { }); }, true),
      btn('💾 Save to phone', async function (ev) { var b = ev.target; b.disabled = true; try { var g = await T.toGallery({ abs: kept.abs, name: nm0 }); b.textContent = '✓ ' + (g.where || ''); } catch (er) { b.disabled = false; b.textContent = '⚠ ' + String(er && er.message || er).slice(0, 40); } }, false),
      btn('Close', closeUI, false)
    ]);
  }

  /* The export runs inside the phone's native side. If the page was reloaded / the app came back while it was running or after it finished,
     this picks the job up again: live progress, or the Done screen, or the error. */
  async function recover() {
    var T = plug().T; if (!T || !T.status) return;
    var st; try { st = await T.status(); } catch (_) { return; }
    if (!st || st.state === 'idle' || UI.el) return;
    if (st.state === 'running') {
      UI.cancel = false; UI.paused = false; ui(); setP((st.p || 0) / 100); status('🚀 ' + Math.round(st.p || 0) + '%'); info(X('b') + ' · continues in the background');
      var pb = btn('⏸ Pause', async function () { try { if (!UI.paused) { UI.paused = true; pb.textContent = '▶ Resume'; await T.pause(); } else { UI.paused = false; pb.textContent = '⏸ Pause'; await T.resume(); } } catch (_) { } }, true);
      actions([pb, btn('✕', function () { UI.cancel = true; try { T.cancel(); } catch (_) { } closeUI(); }, false)]);
      var iv = setInterval(async function () {
        try {
          var s2 = await T.status();
          if (!UI.el) { clearInterval(iv); return; }
          if (s2.state === 'running') { setP((s2.p || 0) / 100); status((UI.paused ? '⏸ ' : '🚀 ') + Math.round(s2.p || 0) + '%'); }
          else { clearInterval(iv); closeUI(); recover(); }
        } catch (_) { }
      }, 1000);
      return;
    }
    if (st.state === 'done' && st.result) {
      ui(); var nm0 = (st.result.abs ? String(st.result.abs).split('/').pop() : '') || ('OnellyEdit_Turbo_' + new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19) + '.mp4');
      try {
        var kept = st.result.kept ? { abs: st.result.abs } : await T.keep({ path: st.result.path, name: nm0 });
        setP(1); $q('#tbp').textContent = '✓'; status('✅ Done'); info((st.result.sizeMB || '') + ' MB · ' + X('b') + (st.result.par ? ' · par: ' + st.result.par : ''));
        showDone(T, kept, nm0);
      } catch (e) { fail('the finished video is not available any more'); }
    } else if (st.state === 'failed') fail(String(st.error || 'export failed').slice(0, 200));
    try { await T.clearStatus(); } catch (_) { }
  }

  /* ---------- 3) Turbo's own entry (next to Fast Export and Standard Export) and its own settings sheet ---------- */
  var BPP = { small: .05, bal: .08, best: .14 }, MULT = { 144: 3.2, 240: 2.4, 360: 1.7, 480: 1.3, 540: 1.2, 720: 1, 1080: .85, 1440: .8, 2160: .75 };
  var RES = [144, 240, 360, 480, 540, 720, 1080, 1440, 2160], FPS = [24, 30, 48, 60, 90, 120];
  var CFG = window.__turboCfg = window.__turboCfg || { S: 720, Q: 'bal', F: 30 };
  var SRC = { url: '', h: 0 };
  function probeSource() {
    var c = clips[0]; if (!c || SRC.url === c.url) return;
    SRC.url = c.url; SRC.h = 0;
    var v = document.createElement('video'); v.preload = 'metadata'; v.muted = true;
    v.onloadedmetadata = function () { SRC.h = Math.min(v.videoWidth, v.videoHeight); paintEst(); v.removeAttribute('src'); };
    v.src = c.url;
  }
  function calc() {
    var S = CFG.S, Sx = (SRC.h && S > SRC.h) ? SRC.h : S, ev = function (n) { return Math.max(2, Math.round(n / 2) * 2); };
    var W = ratio >= 1 ? ev(Sx * ratio) : ev(Sx), H = ratio >= 1 ? ev(Sx) : ev(Sx / ratio);
    var br = Math.round(W * H * CFG.F * BPP[CFG.Q] * (MULT[Sx] || 1));
    return { S: S, W: W, H: H, br: br, mb: br * total() / 8 / 1e6 };
  }
  function paintEst() { var e = $q('#tbEst'); if (!e) return; var c = calc(); e.textContent = c.W + '×' + c.H + ' · ' + CFG.F + ' fps · ' + Math.round(c.br / 1000) + ' kbps · ~' + Math.max(1, Math.round(c.mb)) + ' MB'; }
  function sheet() {
    var d = document.getElementById('turboSheet');
    if (!d) {
      d = document.createElement('div'); d.id = 'turboSheet';
      d.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:9990;display:none;background:var(--panel,#1c1a2b);color:var(--text,#fff);border-top:1px solid var(--line,#333);border-radius:18px 18px 0 0;padding:16px 14px calc(env(safe-area-inset-bottom,0px) + 16px);box-shadow:0 -8px 30px #0008;max-height:85vh;overflow:auto;font-family:system-ui,sans-serif';
      document.body.appendChild(d);
    }
    return d;
  }
  function sbtn(label, fn, main) { var b = document.createElement('button'); b.textContent = label; b.style.cssText = 'display:block;width:100%;margin-top:8px;padding:14px;border:0;border-radius:14px;font:700 15px system-ui;color:' + (main ? '#fff' : 'inherit') + ';background:' + (main ? 'var(--violet,#7c5cff)' : 'var(--panel2,#2c2a3f)'); b.onclick = fn; return b; }
  function closeSheet() { var d = document.getElementById('turboSheet'); if (d) d.style.display = 'none'; }
  function chips(title, id, list, cur, label, set) {
    var w = document.createElement('div'); w.innerHTML = '<b style="display:block;margin:10px 0 6px;font-size:15px">' + title + '</b>';
    var r = document.createElement('div'); r.id = id; r.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px'; w.appendChild(r);
    var paint = function () { Array.prototype.forEach.call(r.children, function (b) { var on = String(b.dataset.v) === String(CFG[cur]); b.style.background = on ? 'var(--violet,#7c5cff)' : 'var(--panel2,#2c2a3f)'; b.style.color = on ? '#fff' : 'inherit'; }); };
    list.forEach(function (v) { var b = document.createElement('button'); b.dataset.v = v; b.textContent = label(v); b.style.cssText = 'padding:9px 13px;border:0;border-radius:10px;font:600 14px system-ui'; b.onclick = function () { CFG[cur] = v; paint(); paintEst(); }; r.appendChild(b); });
    paint(); return w;
  }
  function openSettings() {
    if (!clips.length) { try { msg('⚠'); } catch (_) { } return; }
    probeSource();
    var d = sheet(); d.innerHTML = '<b style="display:block;font-size:17px;margin-bottom:2px">' + X('b') + '</b>';
    d.appendChild(chips('Resolution', 'tbRes', RES, 'S', function (h) { return h === 2160 ? '4K' : h === 1440 ? '2K' : h + 'p'; }));
    d.appendChild(chips('Quality', 'tbQ', ['small', 'bal', 'best'], 'Q', function (q) { return q === 'small' ? 'Smallest file' : q === 'bal' ? 'Balanced' : 'Best quality'; }));
    d.appendChild(chips('Frame rate', 'tbF', FPS, 'F', function (f) { return f; }));
    var e = document.createElement('div'); e.id = 'tbEst'; e.style.cssText = 'margin:12px 0 2px;font-size:13px;opacity:.8'; d.appendChild(e);
    d.appendChild(sbtn('🚀 Start Turbo', function () { closeSheet(); onTurbo(); }, true));
    d.appendChild(sbtn('Close', closeSheet, false));
    d.style.display = 'block'; paintEst();
  }
  async function onTurbo() {
    var c = calc(), S = c.S, F = CFG.F, W = c.W, H = c.H, br = c.br;
    var p = plug();
    if (!p.T) return fail(X('miss'));
    var why = refuse();
    if (why) return fail(X('no') + ' ' + why);
    try { var cp = await p.T.caps(); if (!cp || !cp.hw) return fail('no hardware H.264 encoder'); } catch (_) { return fail(X('miss')); }
    try { await runTurbo(S, F, W, H, br); }
    catch (e) {
      if (UI.cancel) { closeUI(); return; }
      console.warn('turbo failed', e);
      fail(String(e && e.message || e).slice(0, 200));
    }
  }
  /* The Export button: when the project is eligible for Fast Export its own sheet opens and Turbo is added under "Standard export".
     Otherwise a small sheet offers Standard export / Turbo. Fast and Standard code are not changed: "Standard" re-clicks Export once. */
  var bypass = false;
  function wire() {
    var exp = document.getElementById('exp'); if (!exp || exp.__turbo) return; exp.__turbo = 1;
    exp.addEventListener('click', function (e) {
      if (bypass) { bypass = false; return; }
      if (!plug().T) return; /* old APK: no Turbo */
      e.stopImmediatePropagation(); e.preventDefault();
      var d = sheet(); d.innerHTML = '<b style="display:block;font-size:17px;margin-bottom:6px">' + L2('exp') + '</b>';
      d.appendChild(sbtn('Standard export', function () { closeSheet(); bypass = true; exp.click(); }, false));
      d.appendChild(sbtn(X('b'), function () { closeSheet(); openSettings(); }, true));
      d.appendChild(sbtn('Close', closeSheet, false));
      d.style.display = 'block';
    }, true);
  }
  function L2(k) { try { return L(k); } catch (_) { return 'Export'; } }
  /* inside Fast Export's own sheet (#fxs): 🚀 Turbo after "Standard export" */
  function injectFxs() {
    var sh = document.getElementById('fxs'), nb = document.getElementById('fxn');
    if (!sh || !nb || !sh.classList.contains('on') || document.getElementById('fxTurbo')) return;
    if (!plug().T) return;
    var b = document.createElement('button'); b.id = 'fxTurbo'; b.textContent = X('b');
    b.onclick = function () { sh.classList.remove('on'); openSettings(); };
    nb.parentNode.insertBefore(b, nb.nextSibling);
  }
  var pending = false;
  new MutationObserver(function () { if (pending) return; pending = true; setTimeout(function () { pending = false; try { wire(); injectFxs(); } catch (_) { } }, 30); })
    .observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  try { wire(); } catch (_) { }
  setTimeout(function () { try { recover(); } catch (_) { } }, 1500);
})();
