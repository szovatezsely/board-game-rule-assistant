<template>
  <footer class="footer">
    <div class="container footer__inner">
      <p class="footer__text">
        <span class="footer__brand">szabály asszisztens<span class="footer__dot">.</span></span>
        A válaszok kizárólag a feltöltött szabálykönyv tartalmára támaszkodnak.
      </p>
      <p class="footer__meta">A szabálykönyv marad a végső döntőbíró.</p>
    </div>
  </footer>
</template>

<style scoped>
.footer {
  border-top: 1px solid var(--line);
  background: var(--bg);
  padding: 30px 0 40px;
  margin-top: 72px;
}

.footer__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px 24px;
  flex-wrap: wrap;
}

.footer__text,
.footer__meta {
  margin: 0;
  font-size: 0.84rem;
  color: var(--text-dim);
}

.footer__brand {
  font-family: var(--font-display);
  color: var(--text);
  margin-right: 10px;
}

.footer__dot {
  color: var(--green);
}

.footer__meta {
  color: var(--text-faint);
}
</style>
