<template>
  <q-icon :name="iconName" :class="tone" :size="size" />
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  type: { type: String, default: 'unknown' },
  size: { type: String, default: '22px' }
})

// Night Archive treats type nuance typographically, not chromatically.
// Folders get marigold (they're navigable, the only truly "active" type).
// Everything else is paper. Captions + small caps carry the type label.
const iconMap = {
  folder:   'mdi-folder-outline',
  video:    'mdi-filmstrip',
  image:    'mdi-image-outline',
  audio:    'mdi-music-note-outline',
  document: 'mdi-file-document-outline',
  code:     'mdi-code-tags',
  archive:  'mdi-package-variant-closed',
  unknown:  'mdi-file-outline'
}

const iconName = computed(() => iconMap[props.type] || iconMap.unknown)
const tone = computed(() => props.type === 'folder' ? 'marigold' : 'paper')
</script>

<style lang="scss" scoped>
.marigold { color: var(--marigold); }
.paper    { color: var(--paper-mute); }
</style>
