import { createRouter, createWebHistory } from 'vue-router'
import GameView from '@/views/GameView.vue'
import LibraryView from '@/views/LibraryView.vue'
import NotFoundView from '@/views/NotFoundView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'library', component: LibraryView },
    { path: '/jatek/:id', name: 'game', component: GameView, props: true },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
