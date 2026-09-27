<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '@/api/client'
import { useGamesStore } from '@/stores/games'

const store = useGamesStore()
const emit = defineEmits<{ created: [id: string] }>()

const title = ref('')
const files = ref<File[]>([])
const dragging = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const ACCEPTED = ['image/jpeg', 'image/png']

const totalSizeMb = computed(
  () => files.value.reduce((sum, f) => sum + f.size, 0) / (1024 * 1024),
)

/**
 * Natural sort so `2. oldal` lands before `10. oldal`. The backend sorts by
 * filename too, but showing the real order here means the user can verify it
 * before spending Groq calls on a mis-ordered rulebook.
 */
const collator = new Intl.Collator('hu', { numeric: true, sensitivity: 'base' })

function addFiles(incoming: FileList | null) {
  if (!incoming) return
  errorMessage.value = null

  const rejected: string[] = []
  const accepted: File[] = []

  Array.from(incoming).forEach((file) => {
    if (ACCEPTED.includes(file.type)) accepted.push(file)
    else rejected.push(file.name)
  })

  if (rejected.length) {
    errorMessage.value =
      `Ezek a fájlok nem JPG vagy PNG formátumúak, ezért kimaradtak: ${rejected.join(', ')}. ` +
      'Az iPhone HEIC képeit előbb JPG-be kell konvertálni.'
  }

  // De-duplicate by name and size so re-picking the same folder is harmless.
  const seen = new Set(files.value.map((f) => `${f.name}:${f.size}`))
  const fresh = accepted.filter((f) => !seen.has(`${f.name}:${f.size}`))

  files.value = [...files.value, ...fresh].sort((a, b) => collator.compare(a.name, b.name))
}

function onDrop(event: DragEvent) {
  dragging.value = false
  addFiles(event.dataTransfer?.files ?? null)
}

function removeFile(index: number) {
  files.value = files.value.filter((_, i) => i !== index)
}

/** Swaps a page with its neighbour; the list order is the upload order. */
function moveFile(index: number, offset: -1 | 1) {
  const target = index + offset
  if (target < 0 || target >= files.value.length) return
  const next = [...files.value]
  ;[next[index], next[target]] = [next[target], next[index]]
  files.value = next
}

/** For pages photographed from the back cover forwards. */
function reverseFiles() {
  files.value = [...files.value].reverse()
}

function reset() {
  title.value = ''
  files.value = []
  errorMessage.value = null
  if (fileInput.value) fileInput.value.value = ''
}

async function submit() {
  if (!files.value.length || submitting.value) return
  submitting.value = true
  errorMessage.value = null
  try {
    const created = await store.createGame(files.value, title.value)
    reset()
    emit('created', created.id)
  } catch (e) {
    errorMessage.value =
      e instanceof ApiError ? e.message : 'A feltöltés nem sikerült.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="upload panel" aria-labelledby="upload-heading">
    <div class="upload__head">
      <span class="eyebrow">Új szabálykönyv</span>
      <h2 id="upload-heading" class="display--sm">Töltsd fel a szabálykönyv oldalait</h2>
      <p class="lead upload__lead">
        Fotózd le a szabálykönyv minden oldalát, majd add meg őket egyszerre. Az
        oldalak fájlnév szerint rendeződnek, de a sorrendet alább módosíthatod.
        Ha a fotókon látszik a nyomtatott oldalszám, a rendszer a feldolgozáskor
        magától is helyreállítja a könyv sorrendjét.
      </p>
    </div>

    <div
      class="dropzone"
      :class="{ 'dropzone--active': dragging }"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <input
        ref="fileInput"
        id="rulebook-files"
        type="file"
        class="sr-only"
        accept="image/jpeg,image/png"
        multiple
        @change="addFiles(($event.target as HTMLInputElement).files)"
      />
      <p class="dropzone__title">Húzd ide a képeket, vagy</p>
      <label for="rulebook-files" class="btn btn--dark btn--sm dropzone__button">
        Képek kiválasztása
      </label>
      <p class="dropzone__hint">JPG vagy PNG, oldalanként egy kép</p>
      <p class="dropzone__hint dropzone__hint--tip">
        Tipp: lehetőleg egyszerre csak EGY oldalt fotózz, ne a kinyitott könyv
        mindkét oldalát. A kéthasábos, kinyitott oldalaknál a beolvasás könnyen
        felcseréli a szövegrészek sorrendjét.
      </p>
    </div>

    <div v-if="files.length" class="filelist">
      <div class="filelist__head">
        <span class="eyebrow eyebrow--plain">
          {{ files.length }} oldal · {{ totalSizeMb.toFixed(1) }} MB
        </span>
        <div class="filelist__actions">
          <button
            v-if="files.length > 1"
            type="button"
            class="filelist__action"
            @click="reverseFiles"
          >
            Sorrend megfordítása
          </button>
          <button type="button" class="filelist__clear" @click="reset">
            Összes törlése
          </button>
        </div>
      </div>
      <ol class="filelist__items">
        <li v-for="(file, index) in files" :key="`${file.name}-${file.size}`">
          <span class="filelist__index">{{ index + 1 }}.</span>
          <span class="filelist__name">{{ file.name }}</span>
          <button
            type="button"
            class="filelist__move"
            :disabled="index === 0"
            :aria-label="`${file.name} feljebb`"
            @click="moveFile(index, -1)"
          >
            ↑
          </button>
          <button
            type="button"
            class="filelist__move"
            :disabled="index === files.length - 1"
            :aria-label="`${file.name} lejjebb`"
            @click="moveFile(index, 1)"
          >
            ↓
          </button>
          <button
            type="button"
            class="filelist__remove"
            :aria-label="`${file.name} eltávolítása`"
            @click="removeFile(index)"
          >
            ✕
          </button>
        </li>
      </ol>
    </div>

    <label class="field upload__title">
      <span class="field__label">A játék neve (nem kötelező)</span>
      <input
        v-model="title"
        class="input"
        type="text"
        maxlength="120"
        placeholder="Például: Catan"
      />
      <span class="field__hint">
        Ha üresen hagyod, a nevet a rendszer a szabálykönyv első oldalairól olvassa le.
      </span>
    </label>

    <p v-if="errorMessage" class="alert alert--error upload__error">
      {{ errorMessage }}
    </p>

    <div class="upload__actions">
      <button
        type="button"
        class="btn btn--primary"
        :disabled="!files.length || submitting"
        @click="submit"
      >
        <span v-if="submitting" class="spinner" aria-hidden="true"></span>
        {{ submitting ? 'Feltöltés…' : 'Feldolgozás indítása' }}
      </button>
      <p class="upload__note muted">
        A feldolgozás a háttérben fut, közben bezárhatod az oldalt.
      </p>
    </div>
  </section>
</template>

<style scoped>
.upload {
  padding: 40px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.upload__head {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.upload__lead {
  font-size: 1rem;
  margin: 0;
}

.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 48px 24px;
  text-align: center;
  border: 1px dashed var(--color-mediumgray);
  background: var(--color-lightgray);
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.dropzone--active {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.dropzone__title {
  margin: 0;
  font-size: 1rem;
}

.dropzone__button {
  cursor: pointer;
}

.dropzone__hint {
  margin: 0;
  font-size: 0.82rem;
  color: var(--text-color-secondary);
}

.dropzone__hint--tip {
  max-width: 52ch;
  line-height: 1.5;
}

.filelist {
  border: 1px solid var(--color-offwhite);
}

.filelist__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--color-offwhite);
  background: var(--color-lightgray);
}

.filelist__clear {
  background: none;
  border: none;
  padding: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-error);
}

.filelist__clear:hover,
.filelist__action:hover {
  text-decoration: underline;
}

.filelist__actions {
  display: flex;
  align-items: center;
  gap: 18px;
}

.filelist__action {
  background: none;
  border: none;
  padding: 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-main);
}

.filelist__move {
  background: none;
  border: none;
  padding: 4px;
  color: var(--color-gray);
  line-height: 1;
}

.filelist__move:hover:not(:disabled) {
  color: var(--color-primary);
}

.filelist__move:disabled {
  opacity: 0.3;
  cursor: default;
}

.filelist__items {
  list-style: none;
  margin: 0;
  padding: 0;
  /* Long rulebooks scroll inside the list instead of pushing the button offscreen. */
  max-height: 260px;
  overflow-y: auto;
}

.filelist__items li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  font-size: 0.88rem;
  border-bottom: 1px solid var(--color-lightgray);
}

.filelist__items li:last-child {
  border-bottom: none;
}

.filelist__index {
  min-width: 26px;
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.filelist__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filelist__remove {
  background: none;
  border: none;
  padding: 4px;
  color: var(--color-gray);
  line-height: 1;
}

.filelist__remove:hover {
  color: var(--color-error);
}

.upload__title {
  margin: 0;
}

.upload__error {
  margin: 0;
}

.upload__actions {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.upload__note {
  margin: 0;
  font-size: 0.82rem;
}

@media (max-width: 700px) {
  .upload {
    padding: 26px 20px;
  }
}
</style>
