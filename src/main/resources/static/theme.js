(function () {
  function getInitialTheme() {
    const stored = localStorage.getItem('pp-theme');
    if (stored === 'dark' || stored === 'light') return stored;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function applyTheme(theme) {
    if (theme === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  }

  // 파싱 즉시 실행 — FOUC 방지
  applyTheme(getInitialTheme());

  document.addEventListener('DOMContentLoaded', function () {
    const btn = document.createElement('button');
    btn.className = 'theme-toggle';
    btn.setAttribute('aria-label', '테마 전환');

    function sync() {
      btn.textContent = document.documentElement.hasAttribute('data-theme') ? '☀' : '☾';
    }

    btn.addEventListener('click', function () {
      const isDark = document.documentElement.hasAttribute('data-theme');
      const next = isDark ? 'light' : 'dark';
      applyTheme(next);
      localStorage.setItem('pp-theme', next);
      sync();
    });

    sync();
    document.body.appendChild(btn);
  });
})();
