<template>
  <section class="gallery-page__section">
    <div class="section-head">
      <div class="section-head__title">
        <span class="caption-sc">Activity</span>
      </div>
      <div class="section-head__actions">
        <button
          v-if="history.length > 0"
          class="btn-link"
          @click="onClear"
        >
          Clear
        </button>
      </div>
    </div>

    <div class="history-card">
      <div v-if="history.length === 0" class="history-card__empty">
        <div class="history-card__empty-title">No runs recorded yet.</div>
        <div class="history-card__empty-sub">
          Finish a backup and the timeline + 14-day chart will populate here.
        </div>
      </div>

      <!-- Sparkline: last 14 days of bytes uploaded (sum per day) -->
      <div v-if="series.max > 0" class="history-card__chart">
        <div class="history-card__chart-head">
          <div class="history-card__chart-title">Bytes backed up · last 14 days</div>
          <div class="history-card__chart-total caption-sc tabular">
            {{ formatSize(series.total) }}
          </div>
        </div>
        <svg
          :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
          class="history-card__sparkline"
          preserveAspectRatio="none"
        >
          <polyline
            :points="sparklinePoints"
            fill="none"
            stroke="var(--marigold)"
            stroke-width="1.6"
            stroke-linejoin="round"
            stroke-linecap="round"
            vector-effect="non-scaling-stroke"
          />
          <polygon
            :points="sparklineFill"
            fill="var(--marigold)"
            opacity="0.14"
          />
          <line
            v-for="(x, i) in gridX"
            :key="i"
            :x1="x" :x2="x" :y1="0" :y2="chartHeight"
            stroke="var(--rule-soft)"
            stroke-width="0.5"
            vector-effect="non-scaling-stroke"
          />
        </svg>
        <div class="history-card__chart-axis caption-sc tabular">
          <span>{{ series.oldestLabel }}</span>
          <span>today</span>
        </div>
      </div>

      <!-- Per-run log -->
      <ul v-if="history.length > 0" class="history-card__list">
        <li
          v-for="rec in history.slice(0, maxRows)"
          :key="rec.startedAt"
          class="history-card__row"
        >
          <div class="history-card__row-dot" :class="dotClass(rec)" />
          <div class="history-card__row-body">
            <div class="history-card__row-title">
              {{ runTitle(rec) }}
            </div>
            <div class="history-card__row-sub tabular">
              {{ runMeta(rec) }}
            </div>
          </div>
          <div class="history-card__row-when caption-sc tabular">
            {{ formatWhen(rec.finishedAt || rec.startedAt) }}
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { formatSize } from '../utils/helpers'

const props = defineProps({
  history: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  // Gallery renders a collapsed 3-row snapshot; /activity renders everything.
  // Default is 10 (the earlier hard-coded cap).
  maxRows: { type: Number, default: 10 }
})
const emit = defineEmits(['clear'])

const chartWidth = 300
const chartHeight = 48

// Bucket the 14-day window into per-day bytes.  Each bucket aligns to the
// local-midnight boundary of its day — crossing midnight with a long-running
// backup is rare enough that bucketing by `finishedAt` is fine.
const series = computed(() => {
  const DAY = 86_400_000
  const now = Date.now()
  const midnight = new Date()
  midnight.setHours(0, 0, 0, 0)
  const todayStart = midnight.getTime()
  const days = 14
  const buckets = new Array(days).fill(0)
  for (const rec of props.history) {
    const when = Number(rec.finishedAt || rec.startedAt || 0)
    if (!when) continue
    const ageDays = Math.floor((todayStart - when) / DAY)
    if (ageDays < 0 || ageDays >= days) continue
    const idx = days - 1 - ageDays
    buckets[idx] += Number(rec.bytes || 0)
  }
  const max = buckets.reduce((a, b) => Math.max(a, b), 0)
  const total = buckets.reduce((a, b) => a + b, 0)
  const oldest = todayStart - (days - 1) * DAY
  const oldestLabel = new Date(oldest).toLocaleDateString(undefined, {
    month: 'short', day: 'numeric'
  })
  return { buckets, max, total, oldestLabel }
})

const sparklinePoints = computed(() => {
  const { buckets, max } = series.value
  if (max === 0) return ''
  const n = buckets.length
  const stepX = chartWidth / (n - 1)
  return buckets
    .map((v, i) => {
      const x = i * stepX
      const y = chartHeight - (v / max) * (chartHeight - 4) - 2
      return `${x},${y}`
    })
    .join(' ')
})

const sparklineFill = computed(() => {
  const pts = sparklinePoints.value
  if (!pts) return ''
  return `0,${chartHeight} ${pts} ${chartWidth},${chartHeight}`
})

const gridX = computed(() => {
  // Light 7-day midpoint hint: one gridline in the middle of the window.
  return [chartWidth / 2]
})

function dotClass(rec) {
  if (rec.error || (rec.failed > 0 && rec.uploaded === 0)) return 'history-card__row-dot--bad'
  if (rec.failed > 0) return 'history-card__row-dot--warn'
  if (rec.uploaded === 0 && rec.skippedDup === 0) return 'history-card__row-dot--idle'
  return 'history-card__row-dot--ok'
}

function runTitle(rec) {
  if (rec.error) return 'Failed'
  if (rec.uploaded === 0 && rec.skippedDup === 0) return 'Nothing new'
  const parts = []
  if (rec.uploaded > 0) parts.push(`${rec.uploaded} uploaded`)
  if (rec.skippedDup > 0) parts.push(`${rec.skippedDup} already on shelf`)
  if (rec.failed > 0) parts.push(`${rec.failed} failed`)
  return parts.join(' · ')
}

function runMeta(rec) {
  const bytes = Number(rec.bytes || 0)
  const duration = Math.max(0, Number(rec.finishedAt || 0) - Number(rec.startedAt || 0))
  const pieces = []
  if (bytes > 0) pieces.push(formatSize(bytes))
  if (duration > 0) pieces.push(formatDuration(duration))
  if (rec.trigger) pieces.push(rec.trigger)
  return pieces.join(' · ')
}

function formatDuration(ms) {
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  if (m < 60) return rem ? `${m}m ${rem}s` : `${m}m`
  const h = Math.floor(m / 60)
  const remM = m % 60
  return remM ? `${h}h ${remM}m` : `${h}h`
}

function formatWhen(ts) {
  if (!ts) return ''
  const now = Date.now()
  const diff = now - ts
  if (diff < 60_000) return 'just now'
  if (diff < 3_600_000) return `${Math.round(diff / 60_000)} min ago`
  if (diff < 86_400_000) return `${Math.round(diff / 3_600_000)} hr ago`
  const days = Math.round(diff / 86_400_000)
  if (days < 7) return `${days}d ago`
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function onClear() { emit('clear') }
</script>

<style lang="scss" scoped>
.history-card {
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 16px;

  &__empty {
    padding: 8px 0;
  }

  &__empty-title {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
    font-weight: 500;
  }

  &__empty-sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 4px;
  }

  &__chart {
    margin-bottom: 12px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--rule-soft);
  }

  &__chart-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 8px;
  }

  &__chart-title {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    letter-spacing: 0.04em;
  }

  &__chart-total {
    color: var(--paper);
    font-size: 13px;
  }

  &__sparkline {
    width: 100%;
    height: 48px;
    display: block;
  }

  &__chart-axis {
    display: flex;
    justify-content: space-between;
    margin-top: 4px;
    color: var(--paper-dim);
    font-size: 10px;
  }

  &__list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 0;
    border-top: 1px solid var(--rule-soft);

    &:first-child { border-top: none; }
  }

  &__row-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--moss);
    flex-shrink: 0;

    &--ok { background: var(--moss, #7EA577); }
    &--warn { background: var(--marigold, #E8A33D); }
    &--bad { background: var(--rust, #C85A3C); }
    &--idle { background: var(--paper-dim); }
  }

  &__row-body {
    flex: 1;
    min-width: 0;
  }

  &__row-title {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
    font-weight: 500;
  }

  &__row-sub {
    font-size: 11px;
    color: var(--paper-mute);
    margin-top: 2px;
  }

  &__row-when {
    color: var(--paper-dim);
    font-size: 11px;
    flex-shrink: 0;
  }
}
</style>
