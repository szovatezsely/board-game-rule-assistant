import { createApp } from 'vue'
import { createPinia } from 'pinia'

// Fonts are bundled rather than fetched from a CDN, so the app renders in its
// intended typography with no outbound requests at runtime.
//
// Only the latin and latin-ext subsets are imported: latin-ext is what carries
// the Hungarian ő and ű, and pulling the Cyrillic/Greek subsets as well would
// roughly triple the font payload for glyphs this UI never renders.
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-ext-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-ext-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-ext-600.css'
import '@fontsource/roboto-slab/latin-500.css'
import '@fontsource/roboto-slab/latin-ext-500.css'
import './assets/styles/main.css'

import App from './App.vue'
import { router } from './router'

createApp(App).use(createPinia()).use(router).mount('#app')
