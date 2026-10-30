<script setup lang="ts">
import { computed } from 'vue'
import type { RoleCounts, RoleMeta } from '@/types'
import { roleTotal, roleConfigurationError } from '@/game/roleConfiguration'
const props = defineProps<{ modelValue: RoleCounts; roles: RoleMeta[]; players: number; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [counts: RoleCounts] }>()
const error = computed(() => roleConfigurationError(props.modelValue, props.players, props.roles))
function adjust(role: RoleMeta, delta: number) {
  if (props.disabled) return
  const count = Math.min(Math.min(props.players, role.maxCount), Math.max(0, (props.modelValue[role.name] ?? 0) + delta))
  emit('update:modelValue', {...props.modelValue, [role.name]: count})
}
</script>
<template>
  <div class="role-config">
    <div class="role-config__list">
      <div v-for="role in roles" :key="role.name" class="role-config__row">
        <img :src="role.icon" alt="" width="36" height="36"/>
        <div class="role-config__name"><strong>{{role.label}}</strong><small>{{role.maxCount===1?'每局 0～1 人':`每局 0～${players} 人`}}</small></div>
        <div class="role-config__counter">
          <button type="button" :aria-label="`减少${role.label}`" :disabled="disabled || !(modelValue[role.name]>0)" @click="adjust(role,-1)">−</button>
          <output :aria-label="`${role.label}人数`">{{modelValue[role.name]??0}}</output>
          <button type="button" :aria-label="`增加${role.label}`" :disabled="disabled || (modelValue[role.name]??0)>=Math.min(players,role.maxCount)" @click="adjust(role,1)">+</button>
        </div>
      </div>
    </div>
    <p class="role-config__total" aria-live="polite">已配置 <strong>{{roleTotal(modelValue)}} / {{players}}</strong> 人</p>
    <el-alert :title="error || '身份人数配置有效；保存后将在开局时随机分配。'" :type="error?'warning':'success'" :closable="false" show-icon/>
    <p class="ww-muted role-config__note">预言家、女巫、守卫、爱神当前仅支持单实例；设为 0 即不启用。猎人、白痴、白狼王可配置多人；警长由选举产生，不占独立身份名额。</p>
  </div>
</template>
<style scoped>
.role-config__list{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:10px}
.role-config__row{display:flex;align-items:center;gap:10px;padding:10px;border:1px solid var(--ww-border);border-radius:12px;background:rgba(255,255,255,.025);min-width:0}
.role-config__row img{object-fit:cover;border-radius:8px;flex:none}
.role-config__name{display:grid;gap:4px;flex:1;min-width:0}.role-config__name strong{font-size:14px}.role-config__name small{font-size:11px;color:var(--ww-text-dim)}
.role-config__counter{display:flex;align-items:center;gap:4px}.role-config__counter button{height:44px;width:44px;border:1px solid var(--ww-border);border-radius:9px;background:rgba(124,58,237,.15);color:var(--ww-text);font-size:22px;cursor:pointer;touch-action:manipulation}.role-config__counter button:disabled{opacity:.35;cursor:default}.role-config__counter button:focus-visible{outline:2px solid var(--ww-primary);outline-offset:2px}.role-config__counter output{min-width:22px;text-align:center;font-variant-numeric:tabular-nums}.role-config__total{font-size:14px}.role-config__note{line-height:1.7;font-size:12px;margin-bottom:0}
@media(max-width:380px){.role-config__list{grid-template-columns:minmax(0,1fr)}.role-config__row{gap:6px;padding:8px}.role-config__counter{gap:0}.role-config__counter button{width:44px}}
</style>
