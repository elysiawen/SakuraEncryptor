<!--
  Picks the folder a share is rooted at.

  It lists through the same resolution the gateway uses, so "it shows up here"
  means "it will list over WebDAV": an unlocked local library, or a cloud
  profile the app can log in to on its own.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="$t('settings.dav.chooseFolder')"
    :width="480"
    :layer="66"
    :busy="loading"
    :error="error"
    @cancel="cancel"
  >
    <div class="row">
      <button class="ghost sm" type="button" :disabled="loading || !target.parent" @click="load(target.parent)">
        {{ $t('settings.dav.up') }}
      </button>
      <span class="mono path" :title="target.path">
        {{ target.path === '/' ? $t('settings.dav.rootPath') : target.path }}
      </span>
    </div>

    <ul class="dirs">
      <li v-if="!loading && !target.directories.length" class="empty">
        {{ $t('settings.dav.emptyDir') }}
      </li>
      <li v-for="name in target.directories" :key="name">
        <button class="dir" type="button" :disabled="loading" @click="enter(name)">
          <span class="glyph">▸</span>
          <span class="name">{{ name }}</span>
        </button>
      </li>
    </ul>

    <template #footer>
      <button class="ghost" type="button" :disabled="loading" @click="cancel">
        {{ $t('common.cancel') }}
      </button>
      <button class="primary" type="button" :disabled="loading" @click="emit('pick', target.path)">
        {{ $t('settings.dav.useFolder') }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import { t } from '../i18n.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** Which library to list: `local`, or `cloud` with a profile. */
  kind: { type: String, default: 'local' },
  profileId: { type: String, default: '' },
  /** Where to start, as the share's current target. */
  path: { type: String, default: '/' },
})

const emit = defineEmits(['cancel', 'pick'])

const target = reactive({ path: '/', parent: '', directories: [] })
const loading = ref(false)
const error = ref('')

async function load(next) {
  loading.value = true
  error.value = ''
  try {
    const res = await window.ske.webdav.browse({
      kind: props.kind,
      profileId: props.profileId,
      path: next || '/',
    })
    if (res?.ok === false) throw new Error(res.error)
    target.path = res.path || '/'
    target.parent = res.parent || ''
    target.directories = res.directories || []
  } catch (err) {
    // The dialog stays open on an empty list: fixing the profile and retrying
    // is one click away, and closing would lose the user's place.
    target.directories = []
    error.value = err?.message || t('errors.operationFailed')
  } finally {
    loading.value = false
  }
}

function enter(name) {
  load(target.path === '/' ? `/${name}` : `${target.path}/${name}`)
}

function cancel() {
  if (loading.value) return
  emit('cancel')
}

watch(
  () => [props.visible, props.kind, props.profileId, props.path],
  () => {
    if (!props.visible) return
    load(props.path || '/')
  },
  { immediate: true },
)
</script>

<style scoped>
.path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--muted);
}

.dirs {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}

.dirs li + li {
  border-top: 1px solid var(--line-soft);
}

.dirs .empty {
  padding: 14px;
  font-size: 12px;
  color: var(--muted);
  border-top: 0;
}

.dir {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 12px;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.dir:hover:not(:disabled) {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}

.dir .glyph {
  color: var(--muted);
  font-size: 11px;
}

.dir .name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
