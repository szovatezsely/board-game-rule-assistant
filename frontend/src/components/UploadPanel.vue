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
      <span class="eyebrow eyebrow--accent">Új szabálykönyv</span>
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
      <span class="dropzone__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
        </svg>
      </span>
      <p class="dropzone__title">Húzd ide a képeket, vagy</p>
      <label for="rulebook-files" class="btn btn--sm dropzone__button">
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
        <span class="eyebrow">
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
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.upload__head {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.upload__lead {
  font-size: 0.98rem;
  margin: 0;
}

.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 24px;
  text-align: center;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-lg);
  background: var(--surface-alt);
  transition:
    border-color var(--transition),
    background var(--transition),
    box-shadow var(--transition);
}

.dropzone--active {
  border-color: var(--green);
  background: var(--green-veil);
  box-shadow: 0 0 0 4px rgba(0, 201, 91, 0.1);
}

.dropzone__icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--green-veil);
  border: 1px solid var(--green-line);
  color: var(--green-bright);
}

.dropzone__title {
  margin: 0;
  font-size: 0.98rem;
  color: var(--text);
}

.dropzone__button {
  cursor: pointer;
}

.dropzone__hint {
  margin: 0;
  font-size: 0.8rem;
  color: var(--text-faint);
}

.dropzone__hint--tip {
  max-width: 54ch;
  line-height: 1.5;
  color: var(--text-dim);
}

.filelist {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.filelist__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-alt);
}

.filelist__actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.filelist__action,
.filelist__clear {
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: transparent;
  font-size: 0.76rem;
  font-weight: 500;
  color: var(--text-dim);
  transition:
    color var(--transition),
    border-color var(--transition),
    background var(--transition);
}

.filelist__action:hover {
  color: var(--text);
  border-color: var(--line-strong);
  background: var(--surface-hover);
}

.filelist__clear:hover {
  color: var(--red);
  border-color: var(--red-line);
  background: var(--red-veil);
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
  gap: 10px;
  padding: 8px 12px 8px 16px;
  font-size: 0.88rem;
  border-bottom: 1px solid var(--line);
  transition: background var(--transition);
}

.filelist__items li:hover {
  background: var(--surface-hover);
}

.filelist__items li:last-child {
  border-bottom: none;
}

.filelist__index {
  min-width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 1px solid var(--line-strong);
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--text-dim);
  font-variant-numeric: tabular-nums;
}

.filelist__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filelist__move,
.filelist__remove {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: none;
  color: var(--text-faint);
  line-height: 1;
  transition:
    color var(--transition),
    background var(--transition);
}

.filelist__move:hover:not(:disabled) {
  color: var(--green-bright);
  background: var(--green-veil);
}

.filelist__move:disabled {
  opacity: 0.25;
  cursor: default;
}

.filelist__remove:hover {
  color: var(--red);
  background: var(--red-veil);
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
  gap: 12px 20px;
  flex-wrap: wrap;
}

.upload__note {
  margin: 0;
  font-size: 0.82rem;
  color: var(--text-faint);
}

@media (max-width: 700px) {
  .upload {
    padding: 22px 18px;
  }

  .dropzone {
    padding: 28px 16px;
  }
}
</style>
