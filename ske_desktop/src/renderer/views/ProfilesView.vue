<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>云端配置</h2>
        <p>每个配置是一套 AList 账号加一套独立的加密密码，可随时切换。</p>
      </div>
      <button class="primary" type="button" @click="openNew">新建配置</button>
    </div>

    <EmptyState
      v-if="!session.profiles.length"
      icon="☁"
      title="还没有云端配置"
      description="添加 AList 服务器地址与账号后，就能浏览加密目录并直接播放。密码会通过系统钥匙串加密保存。"
      action-text="添加第一个配置"
      @action="openNew"
    />

    <div v-else class="list">
      <article
        v-for="profile in session.profiles"
        :key="profile.id"
        class="item"
        :class="{ active: profile.id === session.activeId }"
      >
        <div class="head">
          <div class="title">
            <span class="mark" aria-hidden="true">☁</span>
            <strong>{{ profile.name }}</strong>
            <span v-if="profile.id === session.activeId" class="badge ok">当前使用</span>
            <span v-if="profile.insecure" class="badge warn" title="系统钥匙串不可用，密码以弱保护形式保存">
              钥匙串不可用
            </span>
          </div>
          <div class="server mono" :title="profile.server">{{ profile.server }}</div>
        </div>

        <!-- Facts as pills rather than one run-on sentence: which secrets are
             stored is the thing worth scanning for, so each gets its own mark. -->
        <div class="facts">
          <span class="fact">
            <span class="k">用户</span>
            <span class="v">{{ profile.username || '未填写' }}</span>
          </span>
          <span class="fact" :class="{ on: profile.hasPassword }">
            <i class="dot" aria-hidden="true" />
            登录密码{{ profile.hasPassword ? '已保存' : '未保存' }}
          </span>
          <span class="fact" :class="{ on: profile.hasSkePassword }">
            <i class="dot" aria-hidden="true" />
            加密密码{{ profile.hasSkePassword ? '已保存' : '未保存' }}
          </span>
          <span v-if="profile.basePath" class="fact mono">
            <span class="k">根目录</span>
            <span class="v">{{ profile.basePath }}</span>
          </span>
        </div>

        <div class="actions">
          <button
            class="primary sm"
            type="button"
            :disabled="profile.id === session.activeId"
            @click="use(profile)"
          >
            {{ profile.id === session.activeId ? '使用中' : '切换到此配置' }}
          </button>
          <button class="ghost sm" type="button" @click="openEdit(profile)">编辑</button>
          <button class="danger sm" type="button" @click="remove(profile)">删除</button>
        </div>
      </article>
    </div>

    <ProfileDialog
      :visible="dialogVisible"
      :profile="editing"
      @close="dialogVisible = false"
      @saved="onSaved"
    />

    <ConfirmDialog
      :visible="removal.show"
      title="删除云端配置"
      :description="removalDescription"
      :busy="removal.busy"
      :error="removal.error"
      @cancel="removal.show = false"
      @confirm="confirmRemove"
    />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import EmptyState from '../components/EmptyState.vue'
import ProfileDialog from '../components/ProfileDialog.vue'
import { activateProfile, attempt, removeProfile, session, toast } from '../state/session.js'

const dialogVisible = ref(false)
const editing = ref(null)
const removal = reactive({ show: false, profile: null, busy: false, error: '' })

function openNew() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(profile) {
  editing.value = profile
  dialogVisible.value = true
}

function onSaved() {
  dialogVisible.value = false
  toast(editing.value ? '配置已更新' : '配置已添加', 'ok')
}

function use(profile) {
  attempt(() => activateProfile(profile.id), { success: `已切换到「${profile.name}」` })
}

const removalDescription = computed(() => {
  const name = removal.profile?.name || ''
  return `删除「${name}」？只会移除本地保存的账号与密码，云端文件不受影响。`
})

function remove(profile) {
  removal.profile = profile
  removal.error = ''
  removal.show = true
}

/**
 * The app's own confirmation, not `window.confirm`: the system box is drawn by
 * Chromium in the OS style and breaks every other dialog's look. Errors land
 * inside the dialog so a failed delete does not vanish behind it.
 */
async function confirmRemove() {
  const profile = removal.profile
  if (!profile) return

  removal.busy = true
  removal.error = ''
  try {
    await removeProfile(profile.id)
    removal.show = false
    toast('配置已删除', 'ok')
  } catch (err) {
    removal.error = err.message
  } finally {
    removal.busy = false
  }
}
</script>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.item {
  position: relative;
  padding: 16px 18px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    background 0.15s ease;
}

.item:hover {
  border-color: color-mix(in srgb, var(--accent) 34%, var(--line));
}

/* The live profile: a tinted rail and a wash that fades out to the right, so a
   list of five is readable without reading the badge. */
.item.active {
  border-color: color-mix(in srgb, var(--accent) 52%, transparent);
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--accent) 10%, transparent), transparent 46%),
    var(--panel);
}

.item.active::before {
  content: '';
  position: absolute;
  inset: 12px auto 12px 0;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--accent);
}

.head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.mark {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--accent-strong);
  background: var(--accent-soft);
}

.title strong {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.server {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 46%;
  padding: 3px 9px;
  font-size: 11px;
  color: var(--muted);
  background: color-mix(in srgb, var(--panel-hover) 55%, transparent);
  border: 1px solid var(--line-soft);
  border-radius: 999px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.fact {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  font-size: 11.5px;
  color: var(--text-dim);
  background: color-mix(in srgb, var(--panel-hover) 45%, transparent);
  border: 1px solid var(--line-soft);
  border-radius: 999px;
}

.fact .k {
  color: var(--muted);
}

.fact .v {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* A stored secret is the reassuring state, so it gets the only colour here. */
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--muted-strong);
}

.fact.on .dot {
  background: var(--ok);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ok) 22%, transparent);
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}

/* Destructive action parked at the far end, away from the one that switches. */
.actions .danger {
  margin-left: auto;
}

/* Same compact button the other list views use; `.sm` is per-view, not global. */
.sm {
  padding: 5px 10px;
  font-size: 12px;
}
</style>
