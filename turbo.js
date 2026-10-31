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
    if (clips.length > 1) return 'several clips';
    if (bgm) return 'background (level 4)';
    if (window.FADE && (FADE.i || FADE.o)) return 'fade in/out';
    var c = clips[0];
    if (c.speed !== 1 || c.rot || c.flip) return 'speed / rotate / flip';
    var f = c.f || PRESETS.none;
    if ((f.sep || 0) > 0.001) return 'sepia filter';
    var base = { url: 1, dur: 1, in: 1, out: 1, speed: 1, rot: 1, flip: 1, vol: 1, f: 1, name: 1, id: 1 };
    for (var k in c) {
      if (base[k] || !c[k]) continue;
      if (typeof c[k] === 'object' && !Object.keys(c[k]).length) continue;
      return 'clip effect (' + k + ')';
    }
    for (var i = 0; i < ovs.length; i++) {
      var o = ovs[i], el = OE[o.id];
      if (!el) return 'overlay missing';
      var isV = o.kind === 'vid' || (el.querySelector && el.querySelector('video'));
      if (isV) {
        if (!o.val) return 'overlay video file missing';
      }
      if (o.bm) return 'blend mode';
      if (o.ck && o.ck.on) return 'chroma key';
      var ts = o.ts || {};
      if (ts.wd || ts.ai === 'type') return 'typing / word-by-word text (level 4)';
      if (isV && ((o.kf && o.kf.length) || (ts.ai && ts.ai !== 'none') || (ts.ao && ts.ao !== 'none') || ts.mv)) return 'moving / animated overlay VIDEO (level 4)';
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
    var done = false;
    try { /* fast way: raw bytes over 127.0.0.1 in 48 MB pieces (no base64); pausing works between pieces */
      var u = await T.upInfo(), PC = 50331648;
      if (u && u.port) {
        for (var o = 0; o < blob.size; o += PC) {
          await gate();
          var end = Math.min(blob.size, o + PC);
          await new Promise(function (res, rej) {
            var x = new XMLHttpRequest();
            x.open('POST', 'http://127.0.0.1:' + u.port + '/put?name=' + encodeURIComponent(nm) + '&t=' + u.token + '&pos=' + o);
            x.upload.onprogress = function (e) { if (e.lengthComputable) onp((o + e.loaded) / blob.size); };
            var iv = setInterval(function () { if (UI.cancel) { try { x.abort(); } catch (_) { } } }, 300);
            x.onload = function () { clearInterval(iv); x.status === 200 ? res() : rej(new Error('upload ' + x.status)); };
            x.onerror = x.onabort = x.ontimeout = function () { clearInterval(iv); rej(new Error(UI.cancel ? 'cancelled' : 'upload network')); };
            x.send(blob.slice(o, end));
          });
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
      }
      return cv;
    } finally { t = old; }
  }
  function box(cv, W, H) {
    var d = cv.getContext('2d', { willReadFrequently: true }).getImageData(0, 0, W, H), u = new Uint32Array(d.data.buffer), x0 = W, y0 = H, x1 = -1, y1 = -1;
    for (var y = 0; y < H; y++) { var r = y * W, xs = -1, xe = -1; for (var x = 0; x < W; x++) { if (u[r + x] >>> 24) { if (xs < 0) xs = x; xe = x; } } if (xs >= 0) { if (xs < x0) x0 = xs; if (xe > x1) x1 = xe; if (y < y0) y0 = y; y1 = y; } }
    return x1 < 0 ? null : [x0, y0, x1 - x0 + 1, y1 - y0 + 1];
  }

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
  function animTable(o, a, b, W, H, sc0) {
    var STEP = 1 / 60, n = Math.ceil((b - a) / STEP) + 1, tab = [], ts = o.ts || {}, isTxt = o.kind === 'emoji' || o.kind === 'text';
    var hasAnim = (ts.ai && ts.ai !== 'none') || (ts.ao && ts.ao !== 'none');
    var sx = document.createElement('canvas').getContext('2d'), m = null, ar = 1;
    if (!isTxt) { m = OE[o.id].querySelector('img,video'); ar = (m.naturalHeight || m.videoHeight || m.height || 1) / (m.naturalWidth || m.videoWidth || m.width || 1); }
    for (var i = 0; i < n; i++) {
      var tt = Math.min(b, a + i * STEP), st = ovState(o, tt), w = W * st.sc, an = hasAnim ? txAnim(o, tt) : { op: 1, sc: 1, dy: 0 };
      var cx = W * st.x / 100, cy = H * st.y / 100, ag = st.rot * Math.PI / 180, mx = 0, my = 0;
      if (ts.mv) {
        var ew, eh;
        if (isTxt) { sx.font = fontOf(o, w); ew = sx.measureText(o.val).width + .8 * w; eh = 1.4 * w; } else { ew = w; eh = w * ar; }
        var mv = txMove(o, tt - o.from, Math.max(.1, o.to - o.from), W, H, ew, eh, cx, cy);
        if (mv) { mx = mv[0]; my = mv[1]; }
      }
      var dyw = an.dy * w;
      tab.push(+(cx + mx - dyw * Math.sin(ag)).toFixed(2), +(cy + my + dyw * Math.cos(ag)).toFixed(2), +((w / (W * sc0)) * an.sc).toFixed(4), +st.rot.toFixed(3), +an.op.toFixed(3));
    }
    return { t0Ms: a * 1000, stepMs: STEP * 1000, tab: tab };
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
      cp = performance.now() - cp;
      /* the output bitrate never goes (much) above the source's own: a higher one only makes the file bigger */
      try { if (blob.size > 0 && c.dur > 0) { var sv = blob.size * 8 / c.dur * 0.93, cap = Math.max(1.0e6, Math.round(sv * 1.4)); if (br > cap) br = cap; } } catch (_) { }
      /* b) overlays: each one is drawn ONCE (a still picture) / overlay video is decoded once, in order */
      var t1 = performance.now(), recs = [], vk = 0, vidFiles = {}, tm = { vcopy: 0, png: 0, sound: 0 };
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
          var s0 = ovState(o, a), pcs = ovPieces(o, a, b);
          for (var j = 0; j < pcs.length; j++) recs.push({ type: 'vid', path: vnm, fromMs: Math.round(pcs[j].a * 1000), toMs: Math.round(pcs[j].b * 1000), inMs: Math.round(pcs[j].s * 1000), durMs: Math.round((o.dur || 0) * 1000), x: s0.x, y: s0.y, sc: s0.sc, rot: s0.rot, ar: vd.videoHeight / vd.videoWidth });
          continue;
        }
        if (isAnim(o)) { /* moving picture: base picture (in tiles) once + sampled movement table */
          var ab = await animBase(o, a, b, W, H); if (!ab) continue;
          var tl = [];
          for (var q = 0; q < ab.tiles.length; q++) {
            var tq = ab.tiles[q], apng = await new Promise(function (r) { tq.canvas.toBlob(r, 'image/png'); }), apath = 'tov' + i + '_' + q + '.png';
            await T.write({ path: apath, data: await b64(apng), append: false });
            tl.push({ path: apath, ox: tq.ox, oy: tq.oy, w: tq.w, h: tq.h }); tq.canvas.width = tq.canvas.height = 1;
          }
          var tb = animTable(o, a, b, W, H, ab.sc0);
          recs.push({ type: 'anim', tiles: tl, fromMs: Math.round(a * 1000), toMs: Math.round(b * 1000), t0Ms: tb.t0Ms, stepMs: tb.stepMs, tab: tb.tab });
          bump(.12 + .08 * (i + 1) / ovs.length); status('🖼 ' + pct()); continue;
        }
        var cv = drawLayer(o, (a + b) / 2, W, H), bx = box(cv, W, H);
        if (!bx) continue;
        var c2 = document.createElement('canvas'); c2.width = bx[2]; c2.height = bx[3];
        c2.getContext('2d').drawImage(cv, bx[0], bx[1], bx[2], bx[3], 0, 0, bx[2], bx[3]);
        var png = await new Promise(function (r) { c2.toBlob(r, 'image/png'); });
        var path = 'tov' + i + '.png';
        await T.write({ path: path, data: await b64(png), append: false });
        recs.push({ type: 'pic', path: path, x: bx[0], y: bx[1], fromMs: Math.round(a * 1000), toMs: Math.round(b * 1000) });
        bump(.12 + .08 * (i + 1) / ovs.length); status('🖼 ' + pct());
      }
      var mix = await soundP;
      ovMs = performance.now() - t1;
      /* c) GPU render */
      sub = await T.addListener('progress', function (e) {
        if (UI.paused) return;
        bump(.2 + .8 * (e.p || 0) / 100); status('🚀 ' + pct());
        var q = (e.p || 0) / 100, x = $q('#tbe');
        if (q > .004 && r0) { var el = (performance.now() - r0 - (pzRef ? pzRef.ms : 0)) / 1000; if (x) x.textContent = fmtT(el * (1 - q) / q) + ' left'; if (q > .004) info(W + '×' + H + ' · ' + X('b') + ' · render ' + (Tt * q / el).toFixed(1) + '× real-time'); }
      });
      bump(.2); status('🚀 ' + pct());
      r0 = performance.now();
      /* the native side starts un-paused: if the person paused earlier, pause it again right away */
      if (UI.paused) setTimeout(function () { try { T.pause(); } catch (_) { } }, 400);
      var res = await T.render({
        src: src, out: 'turbo_out.mp4', width: W, height: H, bitrate: br, fps: F, inMs: inMs, outMs: outMs, volume: c.vol == null ? 1 : c.vol, mix: mix,
        filter: c.f ? { b: c.f.b, c: c.f.c, s: c.f.s, hue: c.f.hue } : null, overlays: recs
      });
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
      var nm0 = 'OnellyEdit_Turbo_' + S + 'p_' + new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19) + '.mp4';
      var kept = await T.keep({ path: res.path, name: nm0 });
      setP(1); $q('#tbp').textContent = '✓'; status('✅ Done'); var e2 = $q('#tbe'); if (e2) e2.textContent = '';
      info(res.sizeMB + ' MB · ' + (res.enc ? 'enc ' + res.enc + ' · ' : '') + X('b') + ' L2 · copy ' + secs(cp) + ' · overlays ' + secs(ovMs) + ' · render ' + secs(rend) + ' (' + (Tt / (rend / 1000)).toFixed(1) + '× real-time)' + (res.ovDiag ? ' · ovl calls/hits ' + res.ovDiag : '') + (res.par ? ' · par: ' + res.par : '') + (res.prof ? ' · ms: ' + res.prof : '') + (' · prep: video-copy ' + secs(tm.vcopy) + ' pictures ' + secs(tm.png) + ' sound ' + secs(tm.sound) + ' (parallel)') + (res.vdec != null && nVid ? ' · ov-decode ' + secs(res.vdec) + ' convert ' + secs(res.vconv) + ' sw×' + res.vsw : '') + (mix ? ' · ov-sound frames ' + res.mixFrames : '') + (nVid ? ' · ov-video frames ' + res.vidShown + ' (' + secs(res.vidMs) + ')' : ''));
      showDone(T, kept, nm0);
    } finally {
      try { if (sub) sub.remove(); } catch (_) { }
      try { await T.clearStatus(); } catch (_) { } /* the result was handed over: nothing left to recover */
      try { await T.remove({ path: 'turbo_out.mp4' }); await T.remove({ path: 'tmix.pcm' }); } catch (_) { }
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
      ui(); var nm0 = 'OnellyEdit_Turbo_' + new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19) + '.mp4';
      try {
        var kept = await T.keep({ path: st.result.path, name: nm0 });
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
