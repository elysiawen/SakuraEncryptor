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
            <strong>{{ profile.name }}</strong>
            <span v-if="profile.id === session.activeId" class="badge ok">当前使用</span>
            <span v-if="profile.insecure" class="badge warn" title="系统钥匙串不可用，密码以弱保护形式保存">
              钥匙串不可用
            </span>
          </div>
          <div class="server mono">{{ profile.server }}</div>
        </div>

        <div class="facts">
          <span>用户 {{ profile.username || '未填写' }}</span>
          <span>{{ profile.hasPassword ? '已保存登录密码' : '未保存登录密码' }}</span>
          <span>{{ profile.hasSkePassword ? '已保存加密密码' : '未保存加密密码' }}</span>
          <span v-if="profile.basePath">根目录 {{ profile.basePath }}</span>
        </div>

        <div class="actions">
          <button
            class="ghost"
            type="button"
            :disabled="profile.id === session.activeId"
            @click="use(profile)"
          >
            {{ profile.id === session.activeId ? '使用中' : '切换到此配置' }}
          </button>
          <button class="ghost" type="button" @click="openEdit(profile)">编辑</button>
          <button class="danger" type="button" @click="remove(profile)">删除</button>
        </div>
      </article>
    </div>

    <ProfileDialog
      :visible="dialogVisible"
      :profile="editing"
      @close="dialogVisible = false"
      @saved="onSaved"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import EmptyState from '../components/EmptyState.vue'
import ProfileDialog from '../components/ProfileDialog.vue'
import { activateProfile, attempt, removeProfile, session, toast } from '../state/session.js'

const dialogVisible = ref(false)
const editing = ref(null)

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

function remove(profile) {
  const ok = window.confirm(`删除配置「${profile.name}」？\n\n只会移除本地保存的账号与密码，云端文件不受影响。`)
  if (!ok) return
  attempt(() => removeProfile(profile.id), { success: '配置已删除' })
}
</script>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.item {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px 18px;
  transition: border-color 0.15s ease;
}

.item.active {
  border-color: color-mix(in srgb, var(--accent) 55%, transparent);
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title strong {
  font-size: 14px;
}

.server {
  font-size: 11.5px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 50%;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 10px 0 14px;
  font-size: 11.5px;
  color: var(--text-dim);
}

.actions {
  display: flex;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}
</style>
