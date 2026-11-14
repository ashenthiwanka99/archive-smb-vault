<template>
  <q-page class="preview-page">
    <div class="preview-page__statusbar-pad safe-top" />
    <q-toolbar class="preview-page__toolbar">
      <button
        class="preview-page__icon-btn"
        aria-label="Back"
        @click="goBack"
      >
        <q-icon name="mdi-arrow-left" size="var(--icon-md)" />
      </button>
      <q-toolbar-title class="preview-page__title">{{ fileName }}</q-toolbar-title>
      <button
        class="preview-page__icon-btn"
        :aria-label="isStarred ? 'Unpin' : 'Pin to starred'"
        @click="toggleStar"
      >
        <q-icon
          :name="isStarred ? 'mdi-star' : 'mdi-star-outline'"
          size="var(--icon-md)"
          :class="isStarred ? 'marigold' : 'paper-mute'"
        />
      </button>
      <button
        class="preview-page__icon-btn"
        aria-label="More actions"
      >
        <q-icon name="mdi-dots-vertical" size="var(--icon-md)" class="paper-mute" />
        <q-menu>
          <q-list dense style="min-width: 200px;">
            <q-item clickable v-close-popup @click="downloadFile">
              <q-item-section avatar>
                <q-icon name="mdi-download-outline" size="var(--icon-md)" />
              </q-item-section>
              <q-item-section>Download</q-item-section>
            </q-item>
            <q-item clickable v-close-popup @click="shareFile">
              <q-item-section avatar>
                <q-icon name="mdi-share-variant" size="var(--icon-md)" />
              </q-item-section>
              <q-item-section>Share</q-item-section>
            </q-item>
            <q-separator />
            <q-item clickable v-close-popup @click="showInfo = !showInfo">
              <q-item-section avatar>
                <q-icon name="mdi-information-outline" size="var(--icon-md)" />
              </q-item-section>
              <q-item-section>File details</q-item-section>
            </q-item>
          </q-list>
        </q-menu>
      </button>
    </q-toolbar>

    <div class="preview-page__content">
      <!-- Image -->
      <div
        v-if="fileType === 'image'"
        v-touch-swipe.mouse.horizontal="onSwipe"
        class="preview-page__image-wrap"
      >
        <button
          v-if="hasPrev"
          class="preview-page__nav preview-page__nav--prev"
          aria-label="Previous image"
          @click="stepSibling(-1)"
        >
          <q-icon name="mdi-chevron-left" size="28px" />
        </button>
        <button
          v-if="hasNext"
          class="preview-page__nav preview-page__nav--next"
          aria-label="Next image"
          @click="stepSibling(1)"
        >
          <q-icon name="mdi-chevron-right" size="28px" />
        </button>

        <div v-if="imageLoading" class="preview-page__loading">
          <div class="skeleton-shimmer" style="width: 90vw; height: 300px;" />
          <div class="caption-sc q-mt-md">Loading image…</div>
        </div>
        <div v-else-if="imageError" class="empty-state">
          <div class="empty-state__eyebrow">Image</div>
          <div class="empty-state__title">Cannot display.</div>
          <div class="empty-state__subtitle">{{ imageError }}</div>
        </div>
        <img v-else-if="previewSrc" :src="previewSrc" class="preview-page__image" />

        <div v-if="hasCarousel" class="preview-page__counter caption-sc tabular">
          {{ siblingIndex + 1 }} / {{ imageSiblings.length }}
        </div>
      </div>

      <!-- Video -->
      <div v-else-if="fileType === 'video'" class="preview-page__video-wrap">
        <!-- Unsupported container (.mkv, .avi, .flv, etc.) — hand off to
             an external player rather than showing a broken HTML5 player. -->
        <div v-if="!isInlineVideo" class="preview-page__fallback">
          <FileTypeIcon type="video" size="56px" />
          <div class="preview-page__filename">{{ fileName }}</div>
          <div class="preview-page__filesize caption-sc">
            {{ formatSize(fileSize) }} · {{ videoExt || 'video' }}
          </div>
          <div class="preview-page__video-hint caption-sc">
            This format needs an external player.
          </div>
          <div class="preview-page__actions">
            <button class="btn-archive" :disabled="externalOpening" @click="openExternalPlayer">
              <q-icon :name="externalOpening ? 'mdi-progress-download' : 'mdi-play-circle-outline'" size="16px" />
              {{ externalOpening ? 'Preparing…' : 'Open in player' }}
            </button>
            <button class="btn-ghost" @click="downloadFile">
              <q-icon name="mdi-download-outline" size="16px" /> Download
            </button>
          </div>
        </div>

        <template v-else>
          <div v-if="streamLoading" class="preview-page__loading">
            <div class="skeleton-shimmer" style="width: 90vw; height: 220px;" />
            <div class="caption-sc q-mt-md">Loading video…</div>
          </div>
          <div v-else-if="streamError" class="empty-state">
            <div class="empty-state__eyebrow">Video</div>
            <div class="empty-state__title">Cannot stream.</div>
            <div class="empty-state__subtitle">{{ streamError }}</div>
            <div class="preview-page__actions">
              <button class="btn-archive" :disabled="externalOpening" @click="openExternalPlayer">
                <q-icon :name="externalOpening ? 'mdi-progress-download' : 'mdi-play-circle-outline'" size="16px" />
                {{ externalOpening ? 'Preparing…' : 'Try external player' }}
              </button>
              <button class="btn-ghost" @click="downloadFile">
                <q-icon name="mdi-download-outline" size="16px" /> Download
              </button>
            </div>
          </div>
          <video v-else-if="streamUrl" ref="videoRef" :src="streamUrl" controls
            class="preview-page__video" preload="metadata" playsinline
            @error="onVideoError" />
        </template>
      </div>

      <!-- Audio -->
      <div v-else-if="fileType === 'audio'" class="preview-page__fallback">
        <FileTypeIcon type="audio" size="56px" />
        <div class="preview-page__filename">{{ fileName }}</div>
        <div class="preview-page__filesize caption-sc">{{ formatSize(fileSize) }}</div>
        <div class="preview-page__actions">
          <button class="btn-archive" @click="playAudio">
            <q-icon name="mdi-play" size="16px" /> play
          </button>
          <button class="btn-ghost" @click="downloadFile">
            <q-icon name="mdi-download-outline" size="16px" /> download
          </button>
        </div>
      </div>

      <!-- PDF: hand off to the system viewer -->
      <div v-else-if="isPdf" class="preview-page__fallback">
        <FileTypeIcon type="document" size="56px" />
        <div class="preview-page__filename">{{ fileName }}</div>
        <div class="preview-page__filesize caption-sc">{{ formatSize(fileSize) }}</div>
        <div class="preview-page__actions">
          <button class="btn-archive" :disabled="pdfOpening" @click="openPdfExternal">
            <q-icon :name="pdfOpening ? 'mdi-progress-download' : 'mdi-file-pdf-box'" size="16px" />
            {{ pdfOpening ? 'Preparing…' : 'Open PDF' }}
          </button>
          <button class="btn-ghost" @click="downloadFile">
            <q-icon name="mdi-download-outline" size="16px" /> Download
          </button>
        </div>
      </div>

      <!-- Text / code: inline with syntax highlighting -->
      <div v-else-if="isText" class="preview-page__text-wrap">
        <div class="preview-page__text-head">
          <FileTypeIcon :type="fileType" size="28px" />
          <div class="preview-page__text-meta">
            <div class="preview-page__text-name">{{ fileName }}</div>
            <div class="preview-page__text-sub caption-sc">{{ formatSize(fileSize) }}</div>
          </div>
        </div>
        <TextPreview
          :content="textContent"
          :file-name="fileName"
          :loading="textLoading"
          :error="textError"
        />
      </div>

      <!-- Other -->
      <div v-else class="preview-page__fallback">
        <FileTypeIcon :type="fileType" size="56px" />
        <div class="preview-page__filename">{{ fileName }}</div>
        <div class="preview-page__filesize caption-sc">{{ formatSize(fileSize) }}</div>
        <button class="btn-archive q-mt-lg" @click="downloadFile">
          <q-icon name="mdi-download-outline" size="16px" /> download
        </button>
      </div>
    </div>

    <!-- Metadata panel -->
    <q-slide-transition>
      <div v-if="showInfo" class="preview-page__info">
        <div class="caption-sc preview-page__info-label">File details</div>
        <div class="preview-page__row"><span class="preview-page__key">Name</span><span class="preview-page__val">{{ fileName }}</span></div>
        <div class="preview-page__row"><span class="preview-page__key">Type</span><span class="preview-page__val">{{ fileType }}</span></div>
        <div class="preview-page__row"><span class="preview-page__key">Size</span><span class="preview-page__val tabular">{{ formatSize(fileSize) }}</span></div>
        <div class="preview-page__row"><span class="preview-page__key">Path</span><span class="preview-page__val preview-page__val--wrap">{{ filePath }}</span></div>
        <div class="preview-page__row"><span class="preview-page__key">Shelf</span><span class="preview-page__val">{{ shareId }}</span></div>
      </div>
    </q-slide-transition>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useStarredStore } from '../stores/starred'
import { useDownloadStore } from '../stores/downloads'
import { useSmbStore } from '../stores/smb'
import { usePlayerStore } from '../stores/player'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { formatSize } from '../utils/helpers'
import { friendlyError } from '../utils/sanitize'
import { useSettingsStore } from '../stores/settings'
import FileTypeIcon from '../components/FileTypeIcon.vue'
import TextPreview from '../components/TextPreview.vue'

const ALLOWED_IMAGE_MIME = new Set([
  'image/jpeg', 'image/png', 'image/gif', 'image/webp',
  'image/bmp', 'image/x-icon', 'image/tiff', 'image/heic', 'image/heif', 'image/avif'
])

// Magic-byte sniffers on a base64 prefix. Only the first few bytes are needed.
function detectImageMime(base64) {
  if (!base64 || base64.length < 8) return null
  const head = base64.slice(0, 16)
  // JPEG: /9j/  (FF D8 FF)
  if (head.startsWith('/9j/')) return 'image/jpeg'
  // PNG: iVBORw0K (89 50 4E 47 0D 0A 1A 0A)
  if (head.startsWith('iVBORw0K')) return 'image/png'
  // GIF87a/GIF89a: R0lGOD
  if (head.startsWith('R0lGOD')) return 'image/gif'
  // WEBP: RIFF....WEBP -> base64 starts with UklGR
  if (head.startsWith('UklGR')) return 'image/webp'
  // BMP: Qk  (42 4D)
  if (head.startsWith('Qk')) return 'image/bmp'
  return null
}

const $q = useQuasar()
const route = useRoute()
const router = useRouter()
const starredStore = useStarredStore()
const downloadStore = useDownloadStore()
const smbStore = useSmbStore()
const playerStore = usePlayerStore()

const fileName = computed(() => route.query.name || 'Unknown')
const fileType = computed(() => route.query.type || 'unknown')
const fileSize = computed(() => Number(route.query.size) || 0)
const filePath = computed(() => route.query.path || '')
const shareId = computed(() => route.query.shareId || '')

const imageLoading = ref(false)
const imageError = ref(null)
const showInfo = ref(false)
const previewSrc = ref('')
const streamLoading = ref(false)
const streamError = ref(null)
const streamUrl = ref('')
const streamId = ref('')
const videoRef = ref(null)

// Text / code preview
const textContent = ref('')
const textLoading = ref(false)
const textError = ref('')
const pdfOpening = ref(false)
const externalOpening = ref(false)

// Containers the Android WebView's HTML5 `<video>` element can play natively.
// Everything else falls back to the external-player hand-off — attempting to
// set `<video src>` on an unsupported container just shows a broken player
// with a 0:00 duration and no actual playback (what the user was seeing).
const INLINE_VIDEO_EXTS = new Set([
  'mp4', 'm4v', 'webm', '3gp', '3gpp'
])

const videoExt = computed(() => extOf(fileName.value))
const isInlineVideo = computed(() =>
  fileType.value === 'video' && INLINE_VIDEO_EXTS.has(videoExt.value)
)

const TEXT_EXT = new Set([
  'txt', 'md', 'markdown', 'mdx', 'log', 'text',
  'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'vue',
  'py', 'java', 'kt', 'kts', 'rs', 'go',
  'cpp', 'cc', 'cxx', 'hpp', 'hxx', 'c', 'h',
  'sh', 'bash', 'zsh', 'sql',
  'json', 'xml', 'html', 'htm', 'svg',
  'yml', 'yaml', 'toml', 'ini', 'conf',
  'css', 'scss', 'sass', 'less'
])

function extOf(name) {
  const dot = (name || '').lastIndexOf('.')
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
}

const isPdf = computed(() =>
  fileType.value === 'document' && extOf(fileName.value) === 'pdf'
)

const isText = computed(() => TEXT_EXT.has(extOf(fileName.value)))

const isStarred = computed(() => starredStore.isStarred(filePath.value, shareId.value))

const settingsStore = useSettingsStore()

async function getShareCreds() {
  // Resolve from the share id on the route query if provided, otherwise fall back to
  // the currently active share. Credentials always come from secure storage.
  const share = settingsStore.shares.find(s => s.id === shareId.value) || smbStore.activeShare
  if (!share) return null
  const creds = await settingsStore.getShareCredentials(share.id)
  return {
    host: share.host,
    shareName: share.shareName,
    username: creds.username || '',
    password: creds.password || ''
  }
}

// Parent directory of the current file — derived from the routed path, not
// from smbStore.currentPath (the browser might have navigated elsewhere
// before we opened this preview from Recents/Starred).
const parentDir = computed(() => {
  const p = filePath.value || ''
  const slash = p.lastIndexOf('/')
  return slash >= 0 ? p.slice(0, slash) : ''
})

// Siblings that can be swiped to.  smbStore.files is populated when the
// preview was opened from the file browser in the same directory — if the
// user came from Recents/Starred we might be looking at a different dir's
// listing, in which case the carousel stays disabled (single-file mode).
const imageSiblings = computed(() => {
  if (parentDir.value !== (smbStore.currentPath || '')) return []
  return smbStore.files.filter(f => !f.isDirectory && f.type === 'image')
})
const siblingIndex = computed(() =>
  imageSiblings.value.findIndex(f => f.name === fileName.value)
)
const hasCarousel = computed(() =>
  fileType.value === 'image' && imageSiblings.value.length > 1 && siblingIndex.value !== -1
)
const hasPrev = computed(() => hasCarousel.value && siblingIndex.value > 0)
const hasNext = computed(() => hasCarousel.value && siblingIndex.value < imageSiblings.value.length - 1)

function stepSibling(delta) {
  if (!hasCarousel.value) return
  const next = imageSiblings.value[siblingIndex.value + delta]
  if (!next) return
  const nextPath = parentDir.value ? `${parentDir.value}/${next.name}` : next.name
  router.replace({
    path: route.path,
    query: {
      ...route.query,
      name: next.name,
      path: nextPath,
      size: next.size || 0,
      type: next.type
    }
  })
}

function onSwipe({ direction }) {
  if (!hasCarousel.value) return
  if (direction === 'left') stepSibling(1)
  else if (direction === 'right') stepSibling(-1)
}

async function loadForCurrent() {
  const creds = await getShareCreds()
  if (!creds) {
    const msg = 'Share not available'
    if (fileType.value === 'image') imageError.value = msg
    if (fileType.value === 'video') streamError.value = msg
    return
  }
  if (fileType.value === 'image') {
    imageError.value = null
    previewSrc.value = ''
    imageLoading.value = true
    try {
      const result = await SmbPlugin.readFile({ host: creds.host, share: creds.shareName, path: filePath.value, username: creds.username, password: creds.password })
      if (!result?.data) throw new Error('No data')

      const declared = String(result.mimeType || '').toLowerCase()
      const detected = detectImageMime(result.data)
      // Prefer the detected type if it disagrees with what the server claimed.
      const finalMime = detected || (ALLOWED_IMAGE_MIME.has(declared) ? declared : null)
      if (!finalMime) throw new Error('Unsupported or invalid image data')

      previewSrc.value = `data:${finalMime};base64,${result.data}`
    } catch (e) { imageError.value = friendlyError(e) }
    finally { imageLoading.value = false }
  } else if (fileType.value === 'video') {
    // Only register a stream for formats the inline player can actually
    // render.  Others go straight to the external-player hand-off UI.
    if (!isInlineVideo.value) {
      streamLoading.value = false
      streamUrl.value = ''
      streamError.value = null
    } else {
      streamLoading.value = true
      streamError.value = null
      try {
        const result = await SmbPlugin.getStreamUrl({ host: creds.host, share: creds.shareName, path: filePath.value, username: creds.username, password: creds.password })
        streamUrl.value = result.localPath
        streamId.value = result.streamId || ''
      } catch (e) { streamError.value = friendlyError(e) }
      finally { streamLoading.value = false }
    }
  } else if (isText.value) {
    // Inline text/code preview — reuse readFile (capped at 25 MB native-side)
    // and convert the base64 payload to a decoded string.
    textLoading.value = true
    try {
      const result = await SmbPlugin.readFile({
        host: creds.host, share: creds.shareName, path: filePath.value,
        username: creds.username, password: creds.password
      })
      if (!result?.data) throw new Error('No data')
      // atob returns a binary string; decode through Uint8Array for UTF-8.
      const bin = atob(result.data)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      textContent.value = new TextDecoder('utf-8', { fatal: false }).decode(bytes)
    } catch (e) {
      textError.value = friendlyError(e)
    } finally {
      textLoading.value = false
    }
  }
}

onMounted(loadForCurrent)
// Swiping between siblings updates the route without re-mounting the page, so
// we watch the path to trigger a reload.  Skip if nothing changed.
watch(filePath, (n, o) => {
  if (n && n !== o) loadForCurrent()
})

async function openExternalPlayer() {
  if (externalOpening.value) return
  externalOpening.value = true
  try {
    const creds = await getShareCreds()
    if (!creds) throw new Error('Share not available')
    // Reuse the PDF hand-off: shareFile() downloads the file to cache and
    // pops Android's chooser, letting VLC / MX Player / anything installed
    // handle formats the WebView doesn't know how to play.
    await SmbPlugin.shareFile({
      host: creds.host, share: creds.shareName, path: filePath.value,
      username: creds.username, password: creds.password
    })
  } catch (e) {
    $q.notify({ message: `Could not open: ${friendlyError(e)}`, color: 'negative', timeout: 3000 })
  } finally {
    externalOpening.value = false
  }
}

function onVideoError() {
  // The inline player hit an error (usually codec inside an otherwise
  // "supported" container).  Surface the fallback UI with an external-
  // player button rather than leaving a stuck broken-video element.
  streamError.value = 'Could not play inline. Try an external player.'
}

async function openPdfExternal() {
  if (pdfOpening.value) return
  pdfOpening.value = true
  try {
    const creds = await getShareCreds()
    if (!creds) throw new Error('Share not available')
    // shareFile() downloads to cache and pops Android's chooser — perfect
    // for handing PDFs off to Drive / Adobe / any installed viewer.
    await SmbPlugin.shareFile({
      host: creds.host, share: creds.shareName, path: filePath.value,
      username: creds.username, password: creds.password
    })
  } catch (e) {
    $q.notify({ message: `Could not open PDF: ${friendlyError(e)}`, color: 'negative', timeout: 3500 })
  } finally {
    pdfOpening.value = false
  }
}

// Drop the server-side registration so the SmbFile can be GC'd and we don't
// leak one per preview.  Best-effort — if it fails, the registration is
// cheap (a HashMap entry) and will die with the process.
onBeforeUnmount(() => {
  if (streamId.value) {
    SmbPlugin.releaseStream({ streamId: streamId.value }).catch(() => {})
  }
})

function goBack() { window.history.length > 1 ? router.back() : router.push('/files') }

function toggleStar() {
  starredStore.toggleStar({ name: fileName.value, path: filePath.value, shareId: shareId.value, type: fileType.value, isDirectory: false, size: fileSize.value })
  $q.notify({ message: isStarred.value ? 'Removed from starred' : 'Added to starred', color: 'positive', textColor: 'dark', timeout: 1500 })
}

async function downloadFile() {
  const share = settingsStore.shares.find(s => s.id === shareId.value) || smbStore.activeShare
  if (!share) {
    $q.notify({ message: 'No share available', color: 'negative', timeout: 2000 })
    return
  }
  const id = downloadStore.enqueue({
    name: fileName.value, path: filePath.value, shareId: share.id,
    host: share.host, shareName: share.shareName,
    size: fileSize.value, type: fileType.value
  })
  $q.notify({ message: id ? `Download queued: ${fileName.value}` : 'Already downloading', color: id ? 'positive' : 'warning', textColor: 'dark', timeout: 2000 })
}

async function shareFile() {
  try {
    const creds = await getShareCreds()
    if (!creds) throw new Error('No share available')
    await SmbPlugin.shareFile({ host: creds.host, share: creds.shareName, path: filePath.value, username: creds.username, password: creds.password })
  } catch (e) {
    $q.notify({ message: `Share failed: ${friendlyError(e)}`, color: 'negative', timeout: 3000 })
  }
}

async function playAudio() {
  try {
    await playerStore.play({
      name: fileName.value,
      path: filePath.value,
      shareId: shareId.value
    })
  } catch (e) {
    $q.notify({ message: `Play failed: ${friendlyError(e)}`, color: 'negative', timeout: 3000 })
  }
}
</script>

<style lang="scss" scoped>
.preview-page {
  &__statusbar-pad { background: var(--ink-0); width: 100%; }

  &__toolbar {
    background: var(--ink-0);
    border-bottom: 1px solid var(--rule);
    min-height: 56px;
    padding: 0 8px;
    gap: 4px;
  }

  &__title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    padding-left: 8px;
  }

  &__icon-btn {
    width: var(--tap-min);
    height: var(--tap-min);
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    transition: background var(--dur-fast) var(--ease-archive);

    &:active { background: var(--ink-2); }
  }

  &__content {
    display: flex;
    flex-direction: column;
    align-items: center;
    // No min-height — previously forced 60vh + centering, which shoved the
    // image/video below the toolbar on shorter phones and cut off the top.
    // Content now flows directly under the toolbar.
  }

  &__image-wrap, &__video-wrap {
    width: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 12px 12px 20px;
    position: relative;
    touch-action: pan-y;
  }

  &__nav {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    width: 44px;
    height: 44px;
    border: none;
    background: rgba(0, 0, 0, 0.42);
    color: var(--paper);
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    backdrop-filter: blur(6px);
    z-index: 2;
    transition: background var(--dur-fast) var(--ease-archive);

    &:active { background: rgba(0, 0, 0, 0.6); }

    &--prev { left: 8px; }
    &--next { right: 8px; }
  }

  &__counter {
    position: absolute;
    bottom: 12px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(0, 0, 0, 0.42);
    color: var(--paper);
    padding: 4px 10px;
    border-radius: 10px;
    letter-spacing: 0.08em;
    pointer-events: none;
  }

  &__image {
    max-width: 100%;
    max-height: 72vh;
    border: 1px solid var(--rule);
    padding: 6px;
    background: var(--ink-1);
  }

  &__video {
    width: 100%;
    max-height: 62vh;
    border-radius: var(--r-sm);
    background: #000;
  }

  &__fallback {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 28px 24px;
  }

  &__video-hint {
    color: var(--paper-mute);
    margin-top: 10px;
    text-align: center;
  }

  &__filename {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 22px;
    color: var(--paper);
    margin-top: 20px;
    text-align: center;
    word-break: break-all;
    letter-spacing: -0.01em;
    line-height: 1.3;
  }

  &__filesize {
    color: var(--paper-dim);
    margin-top: 8px;
  }

  &__actions {
    display: flex;
    gap: 10px;
    margin-top: 28px;
  }

  &__text-wrap {
    width: 100%;
    max-width: 900px;
    margin: 0 auto;
  }

  &__text-head {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 18px 20px;
    border-bottom: 1px solid var(--rule-soft);
  }

  &__text-meta { flex: 1; min-width: 0; }
  &__text-name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  &__text-sub {
    color: var(--paper-dim);
    margin-top: 2px;
  }

  &__info {
    margin: 24px 20px;
    padding: 18px 20px 6px;
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-md);
  }

  &__info-label {
    color: var(--paper-dim);
    margin-bottom: 8px;
  }

  &__row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    padding: 10px 0;
    border-bottom: 1px solid var(--rule-soft);
    font-family: var(--font-body);
    font-size: 13px;

    &:last-child { border-bottom: none; }
  }

  &__key {
    color: var(--paper-dim);
    font-size: 11px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    flex-shrink: 0;
    padding-top: 1px;
  }

  &__val {
    color: var(--paper);
    text-align: right;
    flex: 1;
    &--wrap { word-break: break-all; }
  }
}
</style>
