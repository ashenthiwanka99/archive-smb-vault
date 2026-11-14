<template>
  <Transition name="lock-fade">
    <div v-if="locked" class="lock">
      <div class="lock__inner">
        <q-icon name="mdi-lock-outline" size="40px" class="lock__icon" />
        <div class="lock__brand caption-sc">private archive</div>
        <div class="lock__subtitle">{{ subtitle }}</div>

        <div class="pin-dots" :class="{ 'pin-shake': shaking }">
          <div
            v-for="i in 4"
            :key="i"
            class="pin-dot"
            :class="{
              'pin-dot--filled': pin.length >= i,
              'pin-dot--error': hasError
            }"
          />
        </div>

        <button
          v-if="biometricAvailable && hasPin"
          class="fingerprint-btn"
          @click="tryBiometric"
        >
          <q-icon name="mdi-fingerprint" size="34px" class="fingerprint-icon" />
          <span class="fingerprint-label">Use Fingerprint</span>
        </button>

        <div class="numpad">
          <button
            v-for="key in numpadKeys"
            :key="key.value || 'x' + key.label"
            class="numpad-btn"
            :class="{
              'numpad-btn--empty': key.value === '',
              'numpad-btn--icon': key.value === 'del'
            }"
            :disabled="key.value === ''"
            @click="handleKey(key.value)"
          >
            <q-icon v-if="key.value === 'del'" name="mdi-backspace-outline" size="20px" />
            <span v-else>{{ key.label }}</span>
          </button>
        </div>

        <div v-if="authError" class="lock__error">{{ authError }}</div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { Capacitor } from '@capacitor/core'
import { App } from '@capacitor/app'
import { NativeBiometric } from 'capacitor-native-biometric'
import { useSettingsStore } from '../stores/settings'

// Any absence longer than this re-locks the app on resume.  Short switches
// (e.g. jumping to a share sheet or file picker and coming right back) stay
// unlocked so the app doesn't feel hostile.
const RELOCK_GRACE_MS = 30_000

const settingsStore = useSettingsStore()
const locked = ref(false)
const pin = ref('')
const firstPin = ref('')
const hasError = ref(false)
const shaking = ref(false)
const authError = ref('')
const biometricAvailable = ref(false)

// 'create' → first PIN entry, 'confirm' → re-enter to confirm, 'verify' → unlock.
const setupPhase = ref('verify')

const hasPin = computed(() => !!settingsStore.lockPin)

const subtitle = computed(() => {
  if (setupPhase.value === 'create') return 'Set a 4-digit PIN'
  if (setupPhase.value === 'confirm') return 'Confirm your PIN'
  return 'Enter PIN to unlock'
})

const numpadKeys = [
  { value: '1', label: '1' }, { value: '2', label: '2' }, { value: '3', label: '3' },
  { value: '4', label: '4' }, { value: '5', label: '5' }, { value: '6', label: '6' },
  { value: '7', label: '7' }, { value: '8', label: '8' }, { value: '9', label: '9' },
  { value: '', label: '' },   { value: '0', label: '0' }, { value: 'del', label: '' }
]

function handleKey(value) {
  if (value === '') return
  if (value === 'del') {
    pin.value = pin.value.slice(0, -1)
    hasError.value = false
    authError.value = ''
    return
  }
  if (pin.value.length >= 4) return
  pin.value += value

  if (pin.value.length === 4) {
    if (setupPhase.value === 'create') {
      firstPin.value = pin.value
      pin.value = ''
      setupPhase.value = 'confirm'
    } else if (setupPhase.value === 'confirm') {
      if (pin.value === firstPin.value) {
        settingsStore.setLockPin(pin.value)
        unlock()
      } else {
        triggerError('PINs did not match. Try again.')
        setupPhase.value = 'create'
        firstPin.value = ''
      }
    } else {
      if (pin.value === settingsStore.lockPin) {
        unlock()
      } else {
        triggerError('Wrong PIN.')
      }
    }
  }
}

function triggerError(msg = '') {
  hasError.value = true
  shaking.value = true
  authError.value = msg
  setTimeout(() => {
    shaking.value = false
    pin.value = ''
    hasError.value = false
  }, 500)
}

function unlock() {
  pin.value = ''
  firstPin.value = ''
  authError.value = ''
  locked.value = false
}

async function checkBiometric() {
  try {
    if (!Capacitor.isNativePlatform()) {
      biometricAvailable.value = false
      return
    }
    const result = await NativeBiometric.isAvailable()
    biometricAvailable.value = !!result?.isAvailable
  } catch {
    biometricAvailable.value = false
  }
}

async function tryBiometric() {
  if (!Capacitor.isNativePlatform()) return
  try {
    await NativeBiometric.verifyIdentity({
      reason: 'Unlock Archive',
      title: 'Archive',
      subtitle: 'Use your fingerprint to unlock',
      useFallback: true,
      maxAttempts: 3
    })
    unlock()
  } catch {
    // User cancelled or biometric failed — they're already on the PIN screen.
  }
}

// Reset to the right phase each time the lock comes up.
function resetForShow() {
  pin.value = ''
  firstPin.value = ''
  hasError.value = false
  authError.value = ''
  setupPhase.value = settingsStore.lockPin ? 'verify' : 'create'
}

let backgroundedAt = 0
let appStateHandle = null

async function showLockAndMaybePromptBiometric() {
  locked.value = true
  resetForShow()
  await checkBiometric()
  if (biometricAvailable.value && settingsStore.lockPin) {
    setTimeout(tryBiometric, 300)
  }
}

onMounted(async () => {
  // Show the lock whenever biometricEnabled is on, regardless of whether
  // biometric hardware is available — PIN is the fallback.
  if (settingsStore.biometricEnabled) {
    await showLockAndMaybePromptBiometric()
  }

  // Re-lock on resume: when the app is backgrounded we stamp the time, and
  // on return we check against the grace window.  Short jumps (OS share
  // chooser, file picker) stay unlocked; actual backgrounding re-locks.
  if (Capacitor.isNativePlatform()) {
    try {
      appStateHandle = await App.addListener('appStateChange', async ({ isActive }) => {
        if (!settingsStore.biometricEnabled) return
        if (!isActive) {
          backgroundedAt = Date.now()
          return
        }
        if (locked.value) return
        if (backgroundedAt && Date.now() - backgroundedAt > RELOCK_GRACE_MS) {
          await showLockAndMaybePromptBiometric()
        }
        backgroundedAt = 0
      })
    } catch {
      // App plugin unavailable — cold-start protection still covers the base case.
    }
  }
})

onBeforeUnmount(() => {
  if (appStateHandle?.remove) appStateHandle.remove()
})

// If the user toggles biometricEnabled off/on while the app is running, mirror that.
watch(() => settingsStore.biometricEnabled, (enabled) => {
  if (enabled && !locked.value) {
    locked.value = true
    resetForShow()
    checkBiometric().then(() => {
      if (biometricAvailable.value && settingsStore.lockPin) setTimeout(tryBiometric, 300)
    })
  }
  if (!enabled && locked.value) locked.value = false
})

defineExpose({ locked })
</script>

<style lang="scss" scoped>
.lock {
  position: fixed;
  inset: 0;
  background: var(--ink-0);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;

  &__inner {
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 100%;
    max-width: 340px;
  }

  &__icon {
    color: var(--marigold);
    margin-bottom: 10px;
  }

  &__brand {
    color: var(--marigold);
    letter-spacing: 0.2em;
    margin-bottom: 4px;
  }

  &__subtitle {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-mute);
    margin-bottom: 24px;
  }

  &__error {
    margin-top: 16px;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--rust);
    line-height: 1.4;
    text-align: center;
  }
}

.pin-dots {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}

.pin-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--rule);
  transition: all 0.15s ease;

  &--filled {
    background: var(--marigold);
    border-color: var(--marigold);
  }
  &--error {
    border-color: var(--rust);
    &.pin-dot--filled {
      background: var(--rust);
      border-color: var(--rust);
    }
  }
}

.pin-shake { animation: pin-shake 0.4s ease; }

@keyframes pin-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-8px); }
  40% { transform: translateX(8px); }
  60% { transform: translateX(-6px); }
  80% { transform: translateX(6px); }
}

.fingerprint-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  cursor: pointer;
  margin-bottom: 20px;
  padding: 8px 16px;
  color: var(--paper);

  &:active { opacity: 0.7; }
}

.fingerprint-icon { color: var(--marigold); opacity: 0.9; }

.fingerprint-label {
  font-size: 11px;
  color: var(--paper-mute);
  font-weight: 500;
  letter-spacing: 0.04em;
}

.numpad {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  justify-items: center;
}

.numpad-btn {
  width: 68px;
  height: 68px;
  border-radius: 50%;
  background: var(--ink-1);
  color: var(--paper);
  font-family: var(--font-body);
  font-size: 22px;
  font-weight: 500;
  border: 1px solid var(--rule);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;

  &:active { background: var(--ink-2); }

  &--empty {
    background: transparent;
    border-color: transparent;
    pointer-events: none;
  }

  &--icon {
    background: transparent;
    border-color: transparent;
    color: var(--paper-mute);
    &:active { background: var(--ink-1); }
  }
}

.lock-fade-leave-active { transition: opacity 0.3s ease; }
.lock-fade-leave-to { opacity: 0; }
</style>
