import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSettingsStore } from './settings'
import { friendlyError } from '../utils/sanitize'

export const usePlayerStore = defineStore('player', () => {
  const isPlaying = ref(false)
  const currentTrack = ref(null) // {name, path, shareId, duration, localUrl}
  const queue = ref([])
  const queueIndex = ref(-1)
  const currentTime = ref(0)
  const duration = ref(0)
  const volume = ref(1)
  const repeat = ref('none')
  const shuffle = ref(false)
  const audioElement = ref(null)
  const miniPlayerVisible = ref(false)
  const seekTo = ref(null)
  const lastError = ref(null)

  // Fetch a fresh stream URL using the current credentials in the settings store.
  // This prevents playback from breaking when credentials are rotated after queueing.
  async function refreshStreamUrl(track) {
    if (!track?.shareId || !track?.path) return null
    const settingsStore = useSettingsStore()
    const share = settingsStore.shares.find(s => s.id === track.shareId)
    if (!share) throw new Error('Share no longer available')
    const creds = await settingsStore.getShareCredentials(share.id)
    const result = await SmbPlugin.getStreamUrl({
      host: share.host,
      share: share.shareName,
      path: track.path,
      username: creds.username,
      password: creds.password
    })
    return result?.localPath || null
  }

  async function play(track) {
    lastError.value = null
    currentTrack.value = track
    const idx = queue.value.findIndex(t => t.path === track.path && t.shareId === track.shareId)
    if (idx >= 0) queueIndex.value = idx
    isPlaying.value = true
    miniPlayerVisible.value = true

    if (!track.localUrl) {
      try {
        const url = await refreshStreamUrl(track)
        if (url) currentTrack.value = { ...track, localUrl: url }
        else throw new Error('No stream URL returned')
      } catch (e) {
        lastError.value = friendlyError(e)
        isPlaying.value = false
      }
    }
  }

  async function playAt(index) {
    if (index >= 0 && index < queue.value.length) {
      queueIndex.value = index
      await play(queue.value[index])
    }
  }

  function pause() { isPlaying.value = false }
  function resume() { isPlaying.value = true }

  function stop() {
    isPlaying.value = false
    currentTrack.value = null
    miniPlayerVisible.value = false
    queueIndex.value = -1
    currentTime.value = 0
    duration.value = 0
    lastError.value = null
  }

  function setQueue(tracks) {
    queue.value = tracks
    if (currentTrack.value) {
      const idx = tracks.findIndex(t => t.path === currentTrack.value.path && t.shareId === currentTrack.value.shareId)
      if (idx >= 0) queueIndex.value = idx
    }
  }

  function next() {
    if (queue.value.length === 0) return
    if (repeat.value === 'one') {
      seekTo.value = 0
      isPlaying.value = true
      return
    }
    const idx = queueIndex.value >= 0
      ? queueIndex.value
      : queue.value.findIndex(t => t.path === currentTrack.value?.path)
    const nextIdx = shuffle.value
      ? Math.floor(Math.random() * queue.value.length)
      : idx + 1
    if (nextIdx < queue.value.length) {
      playAt(nextIdx)
    } else if (repeat.value === 'all') {
      playAt(0)
    } else {
      isPlaying.value = false
    }
  }

  function prev() {
    if (queue.value.length === 0) return
    if (currentTime.value > 3) {
      seekTo.value = 0
      return
    }
    const idx = queueIndex.value >= 0
      ? queueIndex.value
      : queue.value.findIndex(t => t.path === currentTrack.value?.path)
    if (idx > 0) {
      playAt(idx - 1)
    } else if (repeat.value === 'all') {
      playAt(queue.value.length - 1)
    }
  }

  function toggleRepeat() {
    const modes = ['none', 'one', 'all']
    repeat.value = modes[(modes.indexOf(repeat.value) + 1) % modes.length]
  }

  function toggleShuffle() { shuffle.value = !shuffle.value }

  const progress = computed(() => duration.value > 0 ? currentTime.value / duration.value : 0)
  const timeLeft = computed(() => duration.value - currentTime.value)

  return {
    isPlaying, currentTrack, queue, queueIndex, currentTime, duration, volume,
    repeat, shuffle, miniPlayerVisible, seekTo, progress, timeLeft, lastError,
    play, playAt, pause, resume, stop, setQueue, next, prev,
    toggleRepeat, toggleShuffle, refreshStreamUrl
  }
})
