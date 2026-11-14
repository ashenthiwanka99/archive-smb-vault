<template>
  <transition name="slide-up">
    <div v-if="playerStore.currentTrack" class="mini-player" @click="$emit('expand')">
      <audio
        ref="audioEl"
        :src="playerStore.currentTrack?.localUrl"
        @timeupdate="onTimeUpdate"
        @ended="onEnded"
        @loadedmetadata="onLoaded"
      />

      <!-- Progress bar thin line at top -->
      <div class="mini-player__progress">
        <div
          class="mini-player__progress-fill"
          :style="{ width: progressPercent + '%' }"
        />
      </div>

      <div class="mini-player__content row items-center no-wrap q-px-sm">
        <!-- Track info -->
        <div class="col mini-player__info">
          <div class="mini-player__name" ref="nameEl">
            <span class="mini-player__name-text" :class="{ scrolling: isNameOverflow }">
              {{ playerStore.currentTrack?.name || 'Unknown' }}
            </span>
          </div>
        </div>

        <!-- Controls -->
        <div class="mini-player__controls row items-center no-wrap">
          <q-btn
            flat dense round
            :icon="playerStore.isPlaying ? 'mdi-pause' : 'mdi-play'"
            color="positive"
            size="md"
            @click.stop="togglePlay"
          />
          <q-btn
            flat dense round
            icon="mdi-skip-next"
            color="positive"
            size="sm"
            @click.stop="nextTrack"
          />
          <q-btn
            flat dense round
            icon="mdi-close"
            color="grey-6"
            size="sm"
            @click.stop="close"
          />
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { ref, watch, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { usePlayerStore } from '../stores/player'

const emit = defineEmits(['expand'])

const playerStore = usePlayerStore()
const audioEl = ref(null)
const nameEl = ref(null)
const isNameOverflow = ref(false)
const currentTime = ref(0)
const duration = ref(0)

const progressPercent = computed(() => {
  if (!duration.value) return 0
  return (currentTime.value / duration.value) * 100
})

function onTimeUpdate() {
  if (!audioEl.value) return
  currentTime.value = audioEl.value.currentTime
  playerStore.currentTime = audioEl.value.currentTime
}

function onLoaded() {
  if (!audioEl.value) return
  duration.value = audioEl.value.duration
  playerStore.duration = audioEl.value.duration
}

function onEnded() {
  playerStore.pause()
  playerStore.next()
}

function togglePlay() {
  if (!audioEl.value) return
  if (playerStore.isPlaying) {
    audioEl.value.pause()
    playerStore.pause()
  } else {
    // HTMLMediaElement.play() returns a Promise that rejects if the browser
    // blocks playback (autoplay policy, no source, etc.). Swallow the rejection
    // so it doesn't surface as an unhandled-rejection error in the console/logs.
    audioEl.value.play().catch(() => {})
    playerStore.resume()
  }
}

function nextTrack() {
  playerStore.next()
}

function close() {
  if (audioEl.value) {
    audioEl.value.pause()
  }
  playerStore.stop()
}

// Watch for track changes to autoplay
watch(() => playerStore.currentTrack?.localUrl, (url) => {
  if (url && audioEl.value) {
    nextTick(() => {
      audioEl.value.load()
      if (playerStore.isPlaying) {
        audioEl.value.play().catch(() => {})
      }
    })
  }
})

// Watch isPlaying toggled externally (from full player)
watch(() => playerStore.isPlaying, (playing) => {
  if (!audioEl.value) return
  if (playing) {
    audioEl.value.play().catch(() => {})
  } else {
    audioEl.value.pause()
  }
})

// Watch seek requests from full player
watch(() => playerStore.seekTo, (time) => {
  if (time !== null && audioEl.value) {
    audioEl.value.currentTime = time
    playerStore.seekTo = null
  }
})

// Check if track name overflows
function checkOverflow() {
  if (nameEl.value) {
    const el = nameEl.value
    isNameOverflow.value = el.scrollWidth > el.clientWidth
  }
}

let resizeObs = null
let trackStop = null
onMounted(() => {
  checkOverflow()
  if (typeof ResizeObserver !== 'undefined' && nameEl.value) {
    resizeObs = new ResizeObserver(() => checkOverflow())
    resizeObs.observe(nameEl.value)
  }
  trackStop = watch(
    () => playerStore.currentTrack?.name,
    () => nextTick(checkOverflow)
  )
})

onBeforeUnmount(() => {
  if (resizeObs) { resizeObs.disconnect(); resizeObs = null }
  if (trackStop) { trackStop(); trackStop = null }
  if (audioEl.value) {
    audioEl.value.pause()
  }
})
</script>

<style lang="scss" scoped>
.mini-player {
  position: fixed;
  bottom: 62px;
  left: 0;
  right: 0;
  height: 56px;
  background: var(--ink-1);
  border-top: 1px solid var(--rule);
  z-index: 100;
  overflow: hidden;

  &__progress {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 1px;
    background: var(--rule-soft);
  }

  &__progress-fill {
    height: 100%;
    background: var(--marigold);
    transition: width 0.3s linear;
  }

  &__content { height: 100%; padding: 0 8px; }

  &__info {
    overflow: hidden;
    padding-left: 10px;
  }

  &__name {
    overflow: hidden;
    white-space: nowrap;
    position: relative;
  }

  &__name-text {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 13px;
    color: var(--paper);
    display: inline-block;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 100%;
  }

  &__controls {
    flex-shrink: 0;
    gap: 2px;
    color: var(--marigold);
  }
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.3s var(--ease-archive), opacity 0.3s var(--ease-archive);
}

.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
  opacity: 0;
}
</style>
