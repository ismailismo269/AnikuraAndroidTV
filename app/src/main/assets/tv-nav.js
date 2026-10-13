/* Anikura TV helper. Injected on every page load; idempotent.
 * Principles: never rewrite site navigation; let the site's own handlers run;
 * only (a) debounce activation, (b) draw focus, (c) provide D-pad movement,
 * (d) give native Back a structured way to close modal -> exit fullscreen -> home. */
(function () {
  if (window.__anikuraTv) return;
  var HOME = location.origin + '/';
  var LOG = function (m) { try { if (window.AnikuraTvBridge) AnikuraTvBridge.log(String(m)); } catch (_) {} };

  /* ---------- SPA route tracking (diagnostic + back logic) ---------- */
  var routeDepth = 0;           // SPA pushState entries made since page load
  var lastNavAt = 0;            // time of last route change
  ['pushState', 'replaceState'].forEach(function (fn) {
    var orig = history[fn];
    history[fn] = function () {
      var r = orig.apply(this, arguments);
      if (fn === 'pushState') routeDepth++;
      lastNavAt = Date.now();
      LOG('history.' + fn + ' -> ' + location.pathname + location.search + ' depth=' + routeDepth);
      return r;
    };
  });
  window.addEventListener('popstate', function () {
    if (routeDepth > 0) routeDepth--;
    lastNavAt = Date.now();
    LOG('popstate -> ' + location.pathname + location.search);
  });

  /* ---------- focus ring ---------- */
  var st = document.createElement('style');
  st.textContent =
    ':focus-visible,.anikura-tv-focused{outline:3px solid #fff!important;outline-offset:3px!important;' +
    'box-shadow:0 0 0 2px rgba(0,0,0,.85)!important}' +
    'a,button,[role=button]{-webkit-tap-highlight-color:transparent}';
  (document.head || document.documentElement).appendChild(st);
  document.addEventListener('focusin', function (e) {
    document.querySelectorAll('.anikura-tv-focused').forEach(function (n) { n.classList.remove('anikura-tv-focused'); });
    if (e.target && e.target.classList) e.target.classList.add('anikura-tv-focused');
  }, true);
  document.addEventListener('focusout', function (e) {
    if (e.target && e.target.classList) e.target.classList.remove('anikura-tv-focused');
  }, true);

  /* ---------- helpers ---------- */
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,' +
    '[role=button],[role=menuitem],[role=tab],[role=option],[role=slider],[tabindex]:not([tabindex="-1"]),video';
  function visible(el) {
    if (el.disabled || el.getAttribute('aria-hidden') === 'true') return false;
    var r = el.getBoundingClientRect(); if (r.width < 2 || r.height < 2) return false;
    var s = getComputedStyle(el); if (s.visibility === 'hidden' || s.display === 'none' || s.pointerEvents === 'none') return false;
    return r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }
  function topModal() {
    var m = Array.prototype.slice.call(document.querySelectorAll('[role=dialog],[aria-modal=true],dialog[open]')).filter(visible);
    return m.length ? m[m.length - 1] : null;
  }
  function scope() { return topModal() || document; }   // focus is trapped inside an open modal
  function candidates() { return Array.prototype.slice.call(scope().querySelectorAll(SEL)).filter(visible); }
  function focusEl(el) {
    try { el.focus({ preventScroll: true }); } catch (_) { el.focus(); }
    el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
  }
  function isCloseControl(el) {
    if (!el) return false;
    var t = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.getAttribute('data-testid') || '')).toLowerCase();
    return /\b(close|sluiten|dismiss)\b/.test(t);
  }
  function findCloseIn(modal) {
    var btns = Array.prototype.slice.call(modal.querySelectorAll('button,[role=button],a[href]')).filter(visible);
    var labelled = btns.filter(isCloseControl); if (labelled.length) return labelled[0];
    // icon-only X button: pick the top-right-most small button that contains an svg and no text
    var mr = modal.getBoundingClientRect();
    var icon = btns.filter(function (b) { return b.querySelector('svg') && !(b.textContent || '').trim(); })
      .filter(function (b) { var r = b.getBoundingClientRect(); return r.width <= 80 && r.height <= 80 && r.top - mr.top < 120 && mr.right - r.right < 120; });
    return icon.length ? icon[0] : null;
  }

  /* ---------- activation guard (one OK press = one activation) ---------- */
  var lastAct = { el: null, t: 0 };
  var ACT_KEYS = { Enter: 1, ' ': 1, Spacebar: 1 };
  var pressed = {};                       // swallow all keydowns until keyup
  document.addEventListener('keyup', function (e) { delete pressed[e.key]; }, true);
  document.addEventListener('keydown', function (e) {
    var k = e.key, a = document.activeElement;
    var typing = a && a.matches && a.matches('input:not([type=checkbox]):not([type=radio]):not([type=range]),textarea,[contenteditable=""],[contenteditable=true]');

    if (ACT_KEYS[k] && !typing) {
      var now = Date.now();
      if (e.repeat || pressed[k]) { e.preventDefault(); e.stopImmediatePropagation(); return; }
      pressed[k] = true;
      // ignore a second activation of the same element within 600 ms, and any
      // activation within 450 ms of a route change (prevents the press that
      // opened the episode from also hitting a control on the new screen)
      if ((lastAct.el === a && now - lastAct.t < 600) || now - lastNavAt < 450) {
        LOG('suppressed activation of ' + (a && a.tagName) + ' dt=' + (now - lastAct.t) + ' navDt=' + (now - lastNavAt));
        e.preventDefault(); e.stopImmediatePropagation(); return;
      }
      lastAct = { el: a, t: now };
      if (a && a.tagName === 'VIDEO') { e.preventDefault(); a.paused ? a.play().catch(function () {}) : a.pause(); }
      if (a && a.matches && a.matches('[role=button],[role=menuitem],[role=tab],[role=option]') && !a.matches('button,a')) {
        e.preventDefault(); a.click();    // non-native buttons don't click on Enter by themselves
      }
      return;                              // native <a>/<button> handle Enter themselves, once
    }
    if (typing && k.indexOf('Arrow') !== 0) return;
    if (k.indexOf('Arrow') !== 0) return;

    // Let sliders / the video element use Left/Right natively
    if (a && a.matches && a.matches('input[type=range],[role=slider],select') && (k === 'ArrowLeft' || k === 'ArrowRight')) return;

    var items = candidates(); if (!items.length) return;
    if (!a || !items.includes(a)) { e.preventDefault(); focusEl(items[0]); return; }
    var ar = a.getBoundingClientRect(), ax = ar.left + ar.width / 2, ay = ar.top + ar.height / 2, best = null, bs = 1e12;
    items.forEach(function (el) {
      if (el === a || a.contains(el) || el.contains(a)) return;
      var r = el.getBoundingClientRect(), dx = r.left + r.width / 2 - ax, dy = r.top + r.height / 2 - ay, p, s;
      if (k === 'ArrowRight') { if (dx <= 2) return; p = dx; s = Math.abs(dy); }
      else if (k === 'ArrowLeft') { if (dx >= -2) return; p = -dx; s = Math.abs(dy); }
      else if (k === 'ArrowDown') { if (dy <= 2) return; p = dy; s = Math.abs(dx); }
      else { if (dy >= -2) return; p = -dy; s = Math.abs(dx); }
      var sc = p + s * 2.25; if (sc < bs) { bs = sc; best = el; }
    });
    if (best) { e.preventDefault(); focusEl(best); }
    else if (k === 'ArrowDown' || k === 'ArrowUp') { /* nothing further: let page scroll */ window.scrollBy(0, k === 'ArrowDown' ? 200 : -200); e.preventDefault(); }
  }, true);

  /* ---------- diagnostics: who clicked what ---------- */
  document.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target.closest('a,button,[role=button]') : e.target;
    if (t) LOG('click ' + t.tagName + ' label="' + (t.getAttribute('aria-label') || t.title || (t.textContent || '').trim().slice(0, 30)) + '" href=' + (t.getAttribute && t.getAttribute('href')) + ' trusted=' + e.isTrusted);
  }, true);

  /* ---------- Back: modal -> fullscreen -> home ---------- */
  function isHome() { return location.pathname === '/' || location.pathname === ''; }
  window.__anikuraTv = {
    // returns what was done, so native code knows whether to handle it further
    back: function () {
      var modal = topModal();
      if (modal) {
        var c = findCloseIn(modal);
        if (c) { LOG('back: clicking site close control'); c.click(); return 'closed-modal'; }
      }
      if (document.fullscreenElement || document.webkitFullscreenElement) {
        (document.exitFullscreen || document.webkitExitFullscreen).call(document); return 'exit-fullscreen';
      }
      if (isHome()) return 'at-home';
      // leaving an anime/episode: prefer the site's own history if we pushed it, otherwise go to catalog
      if (routeDepth > 0 && /^\/anime\//.test(location.pathname)) { history.back(); return 'history-back'; }
      location.assign(HOME); return 'went-home';
    },
    togglePlay: function (mode) {
      var vs = Array.prototype.slice.call(document.querySelectorAll('video'));
      var v = vs.filter(function (x) { return !x.paused; })[0] || vs.filter(function (x) { return x.readyState > 0; })[0] || vs[0];
      if (!v) return 'no-video';
      if (mode === 'play' || (mode === 'toggle' && v.paused)) { v.play().catch(function () {}); return 'play'; }
      v.pause(); return 'pause';
    }
  };
  LOG('tv-nav installed at ' + location.href);
})();
