<template>
  <footer class="footer">
    <div class="container footer__inner">
      <p class="footer__text">
        Társasjáték-szabály asszisztens — a válaszok kizárólag a feltöltött
        szabálykönyv tartalmára támaszkodnak.
      </p>
      <p class="footer__meta">
        A szabálykönyv marad a végső döntőbíró.
      </p>
    </div>
  </footer>
</template>

<style scoped>
.footer {
  border-top: 1px solid var(--color-offwhite);
  padding: 40px 0;
  margin-top: var(--section-padding-half);
}

.footer__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.footer__text,
.footer__meta {
  margin: 0;
  font-size: 0.85rem;
  color: var(--text-color-secondary);
}

.footer__link {
  color: var(--color-main);
}

.footer__link:hover {
  text-decoration: underline;
}
</style>
