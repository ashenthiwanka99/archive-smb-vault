<template>
  <div class="text-preview">
    <div v-if="loading" class="text-preview__loading">
      <q-spinner color="primary" size="18px" class="q-mr-sm" />
      <span>Loading text…</span>
    </div>
    <div v-else-if="error" class="empty-state">
      <div class="empty-state__eyebrow">Text</div>
      <div class="empty-state__title">Cannot preview.</div>
      <div class="empty-state__subtitle">{{ error }}</div>
    </div>
    <pre v-else class="text-preview__pre"><code ref="codeEl" :class="'hljs language-' + language">{{ content }}</code></pre>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import hljs from 'highlight.js/lib/core'

// Register only a focused set of languages.  Keeps bundle small.
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python     from 'highlight.js/lib/languages/python'
import java       from 'highlight.js/lib/languages/java'
import kotlin     from 'highlight.js/lib/languages/kotlin'
import rust       from 'highlight.js/lib/languages/rust'
import go         from 'highlight.js/lib/languages/go'
import cpp        from 'highlight.js/lib/languages/cpp'
import c          from 'highlight.js/lib/languages/c'
import bash       from 'highlight.js/lib/languages/bash'
import sql        from 'highlight.js/lib/languages/sql'
import json       from 'highlight.js/lib/languages/json'
import xml        from 'highlight.js/lib/languages/xml'   // also covers html
import yaml       from 'highlight.js/lib/languages/yaml'
import ini        from 'highlight.js/lib/languages/ini'   // also covers toml-ish
import css        from 'highlight.js/lib/languages/css'
import markdown   from 'highlight.js/lib/languages/markdown'
import plaintext  from 'highlight.js/lib/languages/plaintext'

import 'highlight.js/styles/github-dark.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('python',     python)
hljs.registerLanguage('java',       java)
hljs.registerLanguage('kotlin',     kotlin)
hljs.registerLanguage('rust',       rust)
hljs.registerLanguage('go',         go)
hljs.registerLanguage('cpp',        cpp)
hljs.registerLanguage('c',          c)
hljs.registerLanguage('bash',       bash)
hljs.registerLanguage('sql',        sql)
hljs.registerLanguage('json',       json)
hljs.registerLanguage('xml',        xml)
hljs.registerLanguage('yaml',       yaml)
hljs.registerLanguage('ini',        ini)
hljs.registerLanguage('css',        css)
hljs.registerLanguage('markdown',   markdown)
hljs.registerLanguage('plaintext',  plaintext)

const EXT_LANG = {
  js: 'javascript', mjs: 'javascript', cjs: 'javascript',
  ts: 'typescript', tsx: 'typescript',
  jsx: 'javascript', vue: 'xml',
  py: 'python',
  java: 'java',
  kt: 'kotlin', kts: 'kotlin',
  rs: 'rust',
  go: 'go',
  cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp', hxx: 'cpp',
  c: 'c', h: 'c',
  sh: 'bash', bash: 'bash', zsh: 'bash',
  sql: 'sql',
  json: 'json',
  xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml',
  yml: 'yaml', yaml: 'yaml',
  toml: 'ini', ini: 'ini', conf: 'ini',
  css: 'css', scss: 'css', sass: 'css', less: 'css',
  md: 'markdown', markdown: 'markdown', mdx: 'markdown',
  txt: 'plaintext', log: 'plaintext', text: 'plaintext'
}

const props = defineProps({
  content: { type: String, default: '' },
  fileName: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

const codeEl = ref(null)

const language = computed(() => {
  const name = (props.fileName || '').toLowerCase()
  const dot = name.lastIndexOf('.')
  const ext = dot >= 0 ? name.slice(dot + 1) : ''
  return EXT_LANG[ext] || 'plaintext'
})

onMounted(async () => {
  await nextTick()
  if (codeEl.value && props.content) {
    hljs.highlightElement(codeEl.value)
  }
})
</script>

<style lang="scss" scoped>
.text-preview {
  width: 100%;

  &__loading {
    display: flex;
    align-items: center;
    padding: 32px 20px;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-mute);
  }

  &__pre {
    margin: 0;
    padding: 16px 20px;
    background: var(--ink-0);
    overflow-x: auto;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12.5px;
    line-height: 1.55;
    white-space: pre;
    color: var(--paper);
    border-top: 1px solid var(--rule-soft);

    // Make github-dark play nice on our ink-0 ground.
    :deep(.hljs) {
      background: transparent !important;
      color: var(--paper);
      padding: 0;
    }
  }
}
</style>
