(function () {
  var STATES = ['light', 'dark', 'system'];
  var ICONS  = { light: '☀', dark: '☾', system: '◑' };
  var LABELS = { light: 'Light', dark: 'Dark', system: 'System' };

  function getSystemTheme() {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function getStoredState() {
    var v = localStorage.getItem('pp-theme');
    return (v === 'light' || v === 'dark' || v === 'system') ? v : 'system';
  }

  function applyTheme(state) {
    var effective = (state === 'system') ? getSystemTheme() : state;
    if (effective === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  }

  // 파싱 즉시 실행 — FOUC 방지
  var currentState = getStoredState();
  applyTheme(currentState);

  // 시스템 테마 변경 감지
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
    if (currentState === 'system') applyTheme('system');
  });

  document.addEventListener('DOMContentLoaded', function () {
    function syncAll() {
      document.querySelectorAll('.pp-theme-btn').forEach(function (btn) {
        btn.textContent = ICONS[currentState];
        btn.setAttribute('title', LABELS[currentState]);
        btn.setAttribute('aria-label', LABELS[currentState] + ' 테마');
      });
    }

    function handleClick() {
      var idx = STATES.indexOf(currentState);
      currentState = STATES[(idx + 1) % STATES.length];
      applyTheme(currentState);
      if (currentState === 'system') {
        localStorage.removeItem('pp-theme');
      } else {
        localStorage.setItem('pp-theme', currentState);
      }
      syncAll();
    }

    function makeButton(extraClass) {
      var btn = document.createElement('button');
      btn.className = 'pp-theme-btn ' + (extraClass || 'theme-toggle');
      btn.addEventListener('click', handleClick);
      return btn;
    }

    var slot = document.getElementById('theme-toggle-slot');
    if (slot) {
      // 어드민: navbar 인라인 버튼
      var navBtn = makeButton('theme-toggle-nav');
      slot.appendChild(navBtn);
    } else {
      // 일반 페이지: floating 버튼
      var floatBtn = makeButton('theme-toggle');
      document.body.appendChild(floatBtn);
    }

    syncAll();
  });
})();
