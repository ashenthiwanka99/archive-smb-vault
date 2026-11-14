<template>
  <transition name="player-slide">
    <div v-if="playerStore.currentTrack" class="full-player">
      <!-- Header -->
      <div class="full-player__header row items-center q-px-md q-pt-md safe-top">
        <q-btn
          flat dense round
          icon="mdi-chevron-down"
          color="primary"
          size="md"
          @click="$emit('minimize')"
        />
        <div class="col text-center">
          <div class="full-player__label caption-sc">now playing</div>
        </div>
        <q-btn flat dense round icon="mdi-dots-vertical" size="sm" class="paper-dim" />
      </div>

      <!-- Track Info -->
      <div class="full-player__track-info q-px-lg q-mt-xl">
        <div class="full-player__icon-wrap q-mx-auto q-mb-lg">
          <q-icon name="mdi-music-note-outline" size="56px" class="marigold" />
        </div>
        <div class="full-player__track-name">
          {{ playerStore.currentTrack?.name || 'Unknown track' }}
        </div>
        <div class="full-player__track-path">
          {{ playerStore.currentTrack?.path || '' }}
        </div>
      </div>

      <!-- Seek Bar -->
      <div class="full-player__seek q-px-lg q-mt-lg">
        <div
          class="full-player__seek-bar"
          ref="seekBarEl"
          @click="onSeek"
        >
          <div
            class="full-player__seek-fill"
            :style="{ width: seekPercent + '%' }"
          />
          <div
            class="full-player__seek-thumb"
            :style="{ left: seekPercent + '%' }"
          />
        </div>
        <div class="row justify-between q-mt-xs">
          <span class="full-player__time">{{ formatTime(playerStore.currentTime) }}</span>
          <span class="full-player__time">{{ formatTime(playerStore.duration) }}</span>
        </div>
      </div>

      <!-- Main Controls -->
      <div class="full-player__controls row items-center justify-center q-mt-lg">
        <q-btn
          flat round
          :icon="shuffleIcon"
          size="sm"
          :class="playerStore.shuffle ? 'marigold' : 'paper-dim'"
          @click="playerStore.toggleShuffle()"
        />
        <q-btn flat round icon="mdi-skip-previous" color="primary" size="lg" @click="playerStore.prev()" />
        <button
          class="full-player__play-btn"
          :aria-label="playerStore.isPlaying ? 'Pause' : 'Play'"
          @click="togglePlay"
        >
          <q-icon :name="playerStore.isPlaying ? 'mdi-pause' : 'mdi-play'" size="30px" />
        </button>
        <q-btn flat round icon="mdi-skip-next" color="primary" size="lg" @click="playerStore.next()" />
        <q-btn
          flat round
          :icon="repeatIcon"
          size="sm"
          :class="playerStore.repeat !== 'none' ? 'marigold' : 'paper-dim'"
          @click="playerStore.toggleRepeat()"
        />
      </div>

      <!-- Volume -->
      <div class="full-player__volume row items-center q-px-xl q-mt-md">
        <q-icon name="mdi-volume-low" size="18px" class="paper-dim" />
        <q-slider
          v-model="volume"
          :min="0"
          :max="100"
          color="primary"
          track-color="dark"
          class="col q-mx-sm"
          @update:model-value="onVolumeChange"
        />
        <q-icon name="mdi-volume-high" size="18px" class="paper-dim" />
      </div>

      <!-- Queue -->
      <div class="full-player__queue q-mt-lg">
        <div class="full-player__queue-header caption-sc q-px-lg q-mb-sm">
          queue · {{ playerStore.queue.length }} tracks
        </div>
        <q-scroll-area class="full-player__queue-list">
          <q-list>
            <q-item
              v-for="(track, idx) in playerStore.queue"
              :key="idx"
              clickable
              :class="{ 'queue-active': isActiveTrack(track) }"
              class="queue-item"
              @click="playerStore.playAt(idx)"
            >
              <q-item-section avatar>
                <q-icon
                  :name="isActiveTrack(track) && playerStore.isPlaying ? 'mdi-equalizer' : 'mdi-music-note-outline'"
                  size="18px"
                  :class="isActiveTrack(track) ? 'marigold' : 'paper-dim'"
                />
              </q-item-section>
              <q-item-section>
                <q-item-label class="queue-item__name">{{ track.name }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <span class="queue-item__idx">{{ String(idx + 1).padStart(2, '0') }}</span>
              </q-item-section>
            </q-item>
          </q-list>
        </q-scroll-area>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { ref, computed } from 'vue'
import { usePlayerStore } from '../stores/player'

const emit = defineEmits(['minimize'])

const playerStore = usePlayerStore()
const seekBarEl = ref(null)
const volume = ref(80)

const seekPercent = computed(() => {
  if (!playerStore.duration) return 0
  return (playerStore.currentTime / playerStore.duration) * 100
})

const shuffleIcon = computed(() => 'mdi-shuffle-variant')

const repeatIcon = computed(() => {
  switch (playerStore.repeat) {
    case 'one': return 'mdi-repeat-once'
    case 'all': return 'mdi-repeat'
    default: return 'mdi-repeat-off'
  }
})

function isActiveTrack(track) {
  return track.path === playerStore.currentTrack?.path
}

function togglePlay() {
  if (playerStore.isPlaying) {
    playerStore.pause()
  } else {
    playerStore.resume()
  }
}

function onSeek(e) {
  if (!seekBarEl.value || !playerStore.duration) return
  const rect = seekBarEl.value.getBoundingClientRect()
  const percent = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
  playerStore.seekTo = percent * playerStore.duration
}

function onVolumeChange(val) {
  playerStore.volume = val / 100
}

function formatTime(seconds) {
  if (!seconds || isNaN(seconds)) return '0:00'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}:${String(s).padStart(2, '0')}`
}
</script>

<style lang="scss" scoped>
.full-player {
  position: fixed;
  inset: 0;
  background: var(--ink-0);
  z-index: 200;
  overflow-y: auto;
  display: flex;
  flex-direction: column;

  &__label { color: var(--paper-dim); }

  &__icon-wrap {
    width: 128px;
    height: 128px;
    border-radius: var(--r-md);
    background: var(--ink-1);
    border: 1px solid var(--rule);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__track-info { text-align: center; }

  &__track-name {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 22px;
    line-height: 1.2;
    color: var(--paper);
    word-break: break-word;
    letter-spacing: -0.01em;
  }

  &__track-path {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-dim);
    margin-top: 8px;
    word-break: break-all;
    letter-spacing: 0.04em;
  }

  &__seek {
    .full-player__seek-bar {
      position: relative;
      height: 2px;
      background: var(--rule);
      cursor: pointer;
    }

    .full-player__seek-fill {
      height: 100%;
      background: var(--marigold);
      transition: width 0.2s linear;
    }

    .full-player__seek-thumb {
      position: absolute;
      top: 50%;
      width: 10px;
      height: 10px;
      background: var(--marigold);
      border-radius: 50%;
      transform: translate(-50%, -50%);
    }
  }

  &__time {
    font-family: var(--font-body);
    font-variant-numeric: tabular-nums;
    font-size: 12px;
    color: var(--paper-dim);
    letter-spacing: 0.04em;
  }

  &__controls { gap: 12px; }

  &__play-btn {
    width: 64px;
    height: 64px;
    border-radius: 50%;
    background: var(--marigold);
    color: var(--ink-0);
    border: none;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: background var(--dur-fast) var(--ease-archive);

    &:hover { background: lighten(#E8A33D, 4%); }
    &:active { background: darken(#E8A33D, 6%); }
  }

  &__queue {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  &__queue-header { color: var(--paper-dim); }

  &__queue-list {
    flex: 1;
    min-height: 200px;
  }
}

.queue-item {
  border-bottom: 1px solid var(--rule-soft);
  min-height: 44px;
  padding: 8px 20px;

  &__name {
    font-family: var(--font-body);
    font-size: 14px;
    color: var(--paper-mute);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__idx {
    font-family: var(--font-body);
    font-variant-numeric: tabular-nums;
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.08em;
  }

  &.queue-active {
    background: var(--ink-1);

    .queue-item__name { color: var(--paper); font-weight: 500; }
    .queue-item__idx { color: var(--marigold); }
  }
}

.player-slide-enter-active,
.player-slide-leave-active {
  transition: transform 0.35s var(--ease-archive);
}

.player-slide-enter-from,
.player-slide-leave-to {
  transform: translateY(100%);
}
</style>
