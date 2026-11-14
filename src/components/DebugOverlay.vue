<template>
  <div v-if="visible" class="debug-overlay" :class="{ 'debug-overlay--minimized': minimized }">
    <div class="debug-overlay__header" @click="minimized = !minimized">
      <span class="debug-overlay__title">
        DEBUG · {{ logs.length }} {{ errorCount > 0 ? `· ${errorCount} err` : '' }}
      </span>
      <div class="debug-overlay__actions" @click.stop>
        <button class="debug-overlay__btn" @click="copyAll" title="copy">C</button>
        <button class="debug-overlay__btn" @click="clearLogs" title="clear">X</button>
        <button class="debug-overlay__btn" @click="minimized = !minimized">
          {{ minimized ? '▲' : '▼' }}
        </button>
        <button class="debug-overlay__btn debug-overlay__btn--close" @click="visible = false">✕</button>
      </div>
    </div>

    <div v-if="!minimized" class="debug-overlay__body">
      <div
        v-for="(log, i) in logs"
        :key="i"
        class="debug-overlay__entry"
        :class="`debug-overlay__entry--${log.level}`"
      >
        <div class="debug-overlay__meta">
          <span class="debug-overlay__level">{{ log.level.toUpperCase() }}</span>
          <span class="debug-overlay__time">{{ log.time }}</span>
          <span v-if="log.source" class="debug-overlay__source">{{ log.source }}</span>
        </div>
        <div class="debug-overlay__msg">{{ log.msg }}</div>
        <div v-if="log.stack" class="debug-overlay__stack">{{ log.stack }}</div>
      </div>
      <div v-if="logs.length === 0" class="debug-overlay__empty">
        No logs yet. Errors and warnings will appear here.
      </div>
    </div>
  </div>

  <!-- Floating reopen button when hidden -->
  <button
    v-if="!visible"
    class="debug-overlay__reopen"
    :class="{ 'debug-overlay__reopen--alert': errorCount > 0 }"
    @click="visible = true"
  >
    DBG{{ errorCount > 0 ? ` ${errorCount}` : '' }}
  </button>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { getCurrentInstance } from 'vue'
import { useSmbStore } from '../stores/smb'

const visible = ref(true)
const minimized = ref(false)
const logs = ref([])
const MAX_LOGS = 200

const errorCount = computed(() => logs.value.filter(l => l.level === 'error').length)

function fmtTime() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

function fmtArg(a) {
  if (a == null) return String(a)
  if (typeof a === 'string') return a
  if (a instanceof Error) return `${a.name}: ${a.message}`
  try { return JSON.stringify(a, null, 2).slice(0, 2000) } catch { return String(a) }
}

function push(level, source, ...args) {
  const msg = args.map(fmtArg).join(' ')
  const stack = args.find(a => a instanceof Error)?.stack
  logs.value.push({ level, source, msg, stack, time: fmtTime() })
  if (logs.value.length > MAX_LOGS) logs.value.splice(0, logs.value.length - MAX_LOGS)
}

// Intercept console methods
const originalConsole = {}
function installConsoleHooks() {
  ['log', 'info', 'warn', 'error', 'debug'].forEach(method => {
    originalConsole[method] = console[method]
    console[method] = (...args) => {
      try {
        const level = method === 'warn' ? 'warn' : method === 'error' ? 'error' : 'info'
        push(level, 'console', ...args)
      } catch (e) { /* swallow */ }
      originalConsole[method](...args)
    }
  })
}
function uninstallConsoleHooks() {
  Object.entries(originalConsole).forEach(([k, v]) => { console[k] = v })
}

// Global error handlers
function onWindowError(ev) {
  push('error', 'window', ev.message || 'error', ev.error || '')
  if (ev.error?.stack) logs.value[logs.value.length - 1].stack = ev.error.stack
}
function onRejection(ev) {
  const reason = ev.reason
  const msg = reason instanceof Error ? `${reason.name}: ${reason.message}` : fmtArg(reason)
  push('error', 'promise', msg)
  if (reason?.stack) logs.value[logs.value.length - 1].stack = reason.stack
}

const smbStore = useSmbStore()
let lastError = null
const stopWatchError = watch(() => smbStore.error, val => {
  if (val && val !== lastError) {
    push('error', 'smb-store', val)
    lastError = val
  }
})

function clearLogs() { logs.value = [] }

async function copyAll() {
  const text = logs.value.map(l =>
    `[${l.time}] ${l.level.toUpperCase()} ${l.source ? '(' + l.source + ') ' : ''}${l.msg}${l.stack ? '\n' + l.stack : ''}`
  ).join('\n')
  try {
    if (navigator?.clipboard) {
      await navigator.clipboard.writeText(text)
      push('info', 'debug-overlay', 'logs copied to clipboard')
    }
  } catch (e) {
    push('warn', 'debug-overlay', 'clipboard failed: ' + e.message)
  }
}

// Expose Vue errorHandler hook via the app instance
const instance = getCurrentInstance()

onMounted(() => {
  installConsoleHooks()
  window.addEventListener('error', onWindowError)
  window.addEventListener('unhandledrejection', onRejection)

  const app = instance?.appContext?.app
  if (app) {
    const prev = app.config.errorHandler
    app.config.errorHandler = (err, vm, info) => {
      push('error', `vue:${info || 'unknown'}`, err)
      if (prev) prev(err, vm, info)
    }
    const prevWarn = app.config.warnHandler
    app.config.warnHandler = (msg, vm, trace) => {
      push('warn', 'vue', msg + (trace ? '\n' + trace : ''))
      if (prevWarn) prevWarn(msg, vm, trace)
    }
  }

  push('info', 'debug-overlay', 'overlay initialized')
})

onUnmounted(() => {
  uninstallConsoleHooks()
  window.removeEventListener('error', onWindowError)
  window.removeEventListener('unhandledrejection', onRejection)
  stopWatchError()
})
</script>

<style lang="scss" scoped>
.debug-overlay {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 999999;
  background: rgba(0, 0, 0, 0.92);
  color: #e8e8e8;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.35;
  max-height: 55vh;
  display: flex;
  flex-direction: column;
  border-top: 2px solid #E8A33D;
  box-shadow: 0 -8px 30px rgba(0, 0, 0, 0.6);

  &--minimized { max-height: 30px; }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 10px;
    background: #111;
    border-bottom: 1px solid #333;
    cursor: pointer;
    flex-shrink: 0;
  }

  &__title {
    font-weight: 700;
    letter-spacing: 0.5px;
    color: #E8A33D;
  }

  &__actions { display: flex; gap: 4px; }

  &__btn {
    background: #222;
    color: #eee;
    border: 1px solid #444;
    padding: 2px 8px;
    font-size: 11px;
    font-family: inherit;
    cursor: pointer;
    border-radius: 3px;

    &:active { background: #333; }
    &--close { color: #C85A3C; }
  }

  &__body {
    overflow-y: auto;
    flex: 1;
    padding: 4px 8px 8px;
    -webkit-overflow-scrolling: touch;
  }

  &__empty {
    padding: 12px;
    color: #777;
    text-align: center;
  }

  &__entry {
    border-bottom: 1px dashed #222;
    padding: 4px 0;
    word-break: break-word;

    &--error { color: #ff8a7a; }
    &--warn { color: #ffd27a; }
    &--info { color: #cfe8ff; }
  }

  &__meta {
    display: flex;
    gap: 6px;
    font-size: 9px;
    opacity: 0.7;
    margin-bottom: 2px;
  }
  &__level { font-weight: 700; }
  &__source { color: #7EA577; }

  &__msg { white-space: pre-wrap; }
  &__stack {
    margin-top: 3px;
    padding: 4px 6px;
    background: rgba(255, 255, 255, 0.04);
    font-size: 10px;
    white-space: pre-wrap;
    opacity: 0.85;
  }

  &__reopen {
    position: fixed;
    bottom: 16px;
    right: 16px;
    z-index: 999999;
    padding: 8px 12px;
    background: #111;
    color: #E8A33D;
    border: 1px solid #E8A33D;
    border-radius: 20px;
    font-family: ui-monospace, monospace;
    font-size: 11px;
    font-weight: 700;
    cursor: pointer;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);

    &--alert {
      color: #fff;
      background: #C85A3C;
      border-color: #C85A3C;
      animation: pulse 1.4s infinite;
    }
  }
}

@keyframes pulse {
  0%, 100% { box-shadow: 0 4px 12px rgba(200, 90, 60, 0.5); }
  50% { box-shadow: 0 4px 20px rgba(200, 90, 60, 1); }
}
</style>
