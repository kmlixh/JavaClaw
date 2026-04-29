<script setup>
import { computed } from "vue";

/*
 * 通用 scope 编辑器,绑到 6 大资源对象(skill/knowledge/tool/datasource/llm/agent)的
 * 编辑表单里。把 scopeType / scopeTenantId / appId / scopeUserId 4 个字段统一打包,
 * 后端那侧也是这 4 个字段,前后端一一对齐。
 *
 * v-model:modelValue = { scopeType, scopeTenantId, appId, scopeUserId }
 *
 * 五级语义:
 *   SYSTEM 系统可用    —— 仅系统管理员可见
 *   PUBLIC 公共开放    —— 全员可见
 *   TENANT 租户级开放  —— 仅同租户可见,需要 scopeTenantId
 *   APP    应用级开放  —— 仅同租户+同应用,需要 scopeTenantId + appId
 *   USER   个人私有    —— 仅 owner 可见,需要 scopeUserId
 */

const props = defineProps({
  modelValue: {
    type: Object,
    default: () => ({ scopeType: "PUBLIC", scopeTenantId: "", appId: "", scopeUserId: "" })
  },
  // 后续接入 RBAC 后这两条可以由父组件从 store 注入,做下拉候选
  tenantOptions: { type: Array, default: () => [] },
  appOptions:    { type: Array, default: () => [] }
});
const emit = defineEmits(["update:modelValue"]);

const TYPES = [
  { value: "SYSTEM", label: "系统可用",   hint: "仅系统管理员可见" },
  { value: "PUBLIC", label: "公共开放",   hint: "所有用户可见" },
  { value: "TENANT", label: "租户级开放", hint: "本租户内可见" },
  { value: "APP",    label: "应用级开放", hint: "本租户内、本应用内可见" },
  { value: "USER",   label: "个人私有",   hint: "仅指定用户可见" }
];

const scope = computed({
  get() {
    const v = props.modelValue || {};
    return {
      scopeType: v.scopeType || "PUBLIC",
      scopeTenantId: v.scopeTenantId || "",
      appId: v.appId || "",
      scopeUserId: v.scopeUserId || ""
    };
  },
  set(next) {
    emit("update:modelValue", next);
  }
});

function patch(field, value) {
  emit("update:modelValue", { ...scope.value, [field]: value });
}

const showTenant = computed(() => scope.value.scopeType === "TENANT" || scope.value.scopeType === "APP");
const showApp    = computed(() => scope.value.scopeType === "APP");
const showUser   = computed(() => scope.value.scopeType === "USER");
const activeHint = computed(() => TYPES.find((t) => t.value === scope.value.scopeType)?.hint || "");
</script>

<template>
  <div class="scope-editor">
    <div class="scope-row">
      <label class="scope-label">可见性</label>
      <select
        class="scope-input"
        :value="scope.scopeType"
        @change="patch('scopeType', $event.target.value)"
      >
        <option v-for="t in TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
      </select>
      <span class="scope-hint">{{ activeHint }}</span>
    </div>

    <div v-if="showTenant" class="scope-row">
      <label class="scope-label">归属租户 ID</label>
      <input
        class="scope-input"
        :value="scope.scopeTenantId"
        placeholder="例如 xmap"
        @input="patch('scopeTenantId', $event.target.value)"
      />
    </div>

    <div v-if="showApp" class="scope-row">
      <label class="scope-label">归属应用 ID</label>
      <input
        class="scope-input"
        :value="scope.appId"
        placeholder="应用标识 (空则视为租户内全应用可见)"
        @input="patch('appId', $event.target.value)"
      />
    </div>

    <div v-if="showUser" class="scope-row">
      <label class="scope-label">归属用户 ID</label>
      <input
        class="scope-input"
        :value="scope.scopeUserId"
        placeholder="用户 ID(资源 owner)"
        @input="patch('scopeUserId', $event.target.value)"
      />
    </div>
  </div>
</template>

<style scoped>
.scope-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px dashed #cbd5e1;
  border-radius: 6px;
  background: #f8fafc;
  margin: 10px 0;
}
.scope-row {
  display: grid;
  grid-template-columns: 90px 1fr auto;
  align-items: center;
  gap: 8px;
}
.scope-label {
  font-size: 13px;
  color: #475569;
}
.scope-input {
  height: 30px;
  padding: 0 8px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
}
.scope-hint {
  font-size: 12px;
  color: #94a3b8;
}
</style>
