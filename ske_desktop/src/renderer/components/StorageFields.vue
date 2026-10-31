<!--
  One driver's config fields, rendered from what the server advertises.

  Nothing here knows which driver it is: `select` becomes a dropdown, `bool` a
  checkbox, `text` a textarea, `number` a numeric input. The one exception is a
  field literally named `root_folder_path` — that is a path on this machine, so
  it gets the native folder picker next to the text box.
-->
<template>
  <div class="fields">
    <div v-for="field in fields" :key="field.name" class="field">
      <label :for="`field-${uid}-${field.name}`">
        {{ field.name }}<span v-if="field.required" class="req">*</span>
      </label>

      <select
        v-if="field.type === 'select'"
        :id="`field-${uid}-${field.name}`"
        :value="valueOf(field)"
        @change="set(field, $event.target.value)"
      >
        <option v-for="option in field.options" :key="option" :value="option">{{ option }}</option>
      </select>

      <label v-else-if="field.type === 'bool'" class="check-line">
        <input
          type="checkbox"
          :checked="valueOf(field) === 'true'"
          @change="set(field, $event.target.checked ? 'true' : 'false')"
        />
        <span>{{ field.help || field.name }}</span>
      </label>

      <textarea
        v-else-if="field.type === 'text'"
        :id="`field-${uid}-${field.name}`"
        rows="3"
        :value="valueOf(field)"
        @input="set(field, $event.target.value)"
      />

      <div v-else class="row">
        <input
          :id="`field-${uid}-${field.name}`"
          :type="isNumeric(field) ? 'number' : 'text'"
          :value="valueOf(field)"
          @input="set(field, $event.target.value)"
        />
        <button
          v-if="field.name === 'root_folder_path'"
          class="ghost sm"
          type="button"
          @click="pickFolder(field)"
        >
          选择文件夹
        </button>
      </div>

      <div v-if="field.help && field.type !== 'bool'" class="meta">{{ field.help }}</div>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  fields: { type: Array, default: () => [] },
  /** Plain object mutated in place; the parent keeps the reactive copy. */
  model: { type: Object, required: true },
  /** Distinguishes two forms on one page so their `id`s stay unique. */
  uid: { type: String, default: 'a' },
})

function isNumeric(field) {
  return ['number', 'float', 'int', 'integer'].includes(field.type)
}

function valueOf(field) {
  const raw = props.model[field.name]
  return raw === undefined || raw === null ? field.default : String(raw)
}

function set(field, value) {
  props.model[field.name] = value
}

async function pickFolder(field) {
  const res = await window.ske.dialog.pickDirectory()
  if (res?.ok && res.path) props.model[field.name] = res.path
}
</script>

<style scoped>
.fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.req {
  color: var(--danger);
  margin-left: 2px;
}

.check-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  color: var(--text-dim);
  cursor: pointer;
}

.check-line input {
  width: auto;
  margin-top: 2px;
  accent-color: var(--accent);
}
</style>
