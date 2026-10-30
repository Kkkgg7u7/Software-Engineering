<script setup lang="ts">
/**
 * 身份卡（3D 翻牌）。
 *
 * 设计取舍：
 * - 只有 `revealed === true` 且拿到了 role 才允许翻开，避免把「卡背 + 身份未公开」误当成
 *   可翻的牌；
 * - 翻牌状态是组件内部的 `flipped`，父组件只需决定「这张牌是否允许被看」。
 */
import { computed, ref } from 'vue'
import type { RoleType } from '@/types'

const props = withDefaults(
  defineProps<{
    role?: RoleType | null
    assetName?: string
    label?: string
    skill?: string
    camp?: string
    revealed?: boolean
    size?: 'sm' | 'md' | 'lg'
  }>(),
  {
    role: null,
    assetName: '',
    label: '',
    skill: '',
    camp: '',
    revealed: false,
    size: 'md'
  }
)

/** 内部翻牌状态：默认背面朝上，点击后翻开。 */
const flipped = ref(false)

const canReveal = computed(() => props.revealed && !!props.role)
const faceUp = computed(() => canReveal.value && flipped.value)

const roleKey = computed(() => String(props.assetName || props.role || '').toLowerCase())
const roleClass = computed(() => (roleKey.value ? `role-${roleKey.value}` : ''))
const cardSrc = computed(() => `/assets/roles/${roleKey.value || 'villager'}-card.svg?art=night-v1`)

const roleLabel = computed(() => {
  if (props.label) {
    return props.label
  }
  switch (roleKey.value) {
    case 'white_wolf_king': return '白狼王'
    case 'hunter': return '猎人'
    case 'guard': return '守卫'
    case 'idiot': return '白痴'
    case 'cupid': return '爱神'
    case 'werewolf':
      return '狼人'
    case 'seer':
      return '预言家'
    case 'witch':
      return '女巫'
    case 'villager':
      return '村民'
    default:
      return '未知身份'
  }
})

const campLabel = computed(() => {
  if (props.camp) {
    return ({ VILLAGE: '好人阵营', WEREWOLF: '狼人阵营', LOVERS: '情侣阵营' } as Record<string, string>)[props.camp] || props.camp
  }
  return ['werewolf','white_wolf_king'].includes(roleKey.value) ? '狼人阵营' : '好人阵营'
})

const backHint = computed(() => (canReveal.value ? '点击翻开' : '身份未公开'))

function toggle() {
  if (!canReveal.value) {
    return
  }
  flipped.value = !flipped.value
}
</script>

<template>
  <div
    class="role-card"
    :class="[roleClass, `role-card--${size}`, { 'role-card--flipped': faceUp, 'role-card--can-flip': canReveal }]"
    :role="canReveal ? 'button' : undefined"
    :tabindex="canReveal ? 0 : undefined"
    :aria-label="`身份卡：${faceUp ? roleLabel : backHint}`"
    @click="toggle"
    @keydown.enter.prevent="toggle"
    @keydown.space.prevent="toggle"
  >
    <div class="role-card__inner">
      <!-- 正面：卡背 -->
      <div class="role-card__face role-card__face--front">
        <img class="role-card__art" src="/assets/roles/hidden-back.svg?art=night-v1" alt="身份卡背面" />
        <div class="role-card__veil">
          <span class="role-card__hint" :class="{ 'ww-pulse': canReveal }">{{ backHint }}</span>
        </div>
      </div>

      <!-- 背面：揭晓的角色卡 -->
      <div class="role-card__face role-card__face--back">
        <img class="role-card__art" :src="cardSrc" :alt="roleLabel" />
      </div>
    </div>

    <div v-if="faceUp" class="role-card__info">
      <div class="role-card__info-head">
        <span class="role-card__name-sm">{{ roleLabel }}</span>
        <span class="ww-tag" :class="`ww-tag--${roleKey === 'werewolf' ? 'wolf' : roleKey}`">
          {{ campLabel }}
        </span>
      </div>
      <p v-if="skill" class="role-card__skill">{{ skill }}</p>
    </div>
  </div>
</template>

<style scoped>
.role-card {
  position: relative;
  width: 100%;
  perspective: 1200px;
  outline: none;
}

.role-card--can-flip {
  cursor: pointer;
}

.role-card__inner {
  position: relative;
  width: 100%;
  aspect-ratio: 2 / 3;
  transform-style: preserve-3d;
  transition: transform 0.75s cubic-bezier(0.22, 1, 0.36, 1);
}

.role-card--flipped .role-card__inner {
  transform: rotateY(180deg);
}

.role-card__face {
  position: absolute;
  inset: 0;
  backface-visibility: hidden;
  -webkit-backface-visibility: hidden;
  border-radius: var(--ww-radius);
  overflow: hidden;
  border: 1px solid var(--role-color, var(--ww-border));
  background: var(--ww-panel-solid);
  box-shadow: var(--ww-shadow);
}

.role-card__face--back {
  transform: rotateY(180deg);
  box-shadow: var(--ww-shadow-glow);
}

.role-card__art {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.role-card__veil {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 10px 8px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  text-align: center;
  background: linear-gradient(180deg, rgba(7, 10, 20, 0) 0%, rgba(7, 10, 20, 0.86) 55%, rgba(7, 10, 20, 0.96) 100%);
}

.role-card__veil--solid {
  background: linear-gradient(180deg, rgba(7, 10, 20, 0) 0%, rgba(7, 10, 20, 0.92) 60%);
}

.role-card__hint {
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--ww-text-dim);
}

.role-card__name {
  font-size: 16px;
  letter-spacing: 2px;
}

/* 尺寸 */
.role-card--sm {
  max-width: 108px;
}

.role-card--md {
  max-width: 156px;
}

.role-card--lg {
  max-width: 220px;
}

.role-card--sm .role-card__name,
.role-card--sm .role-card__name-sm {
  font-size: 13px;
  letter-spacing: 1px;
}

.role-card--sm .role-card__hint {
  font-size: 10px;
}

.role-card--lg .role-card__name {
  font-size: 20px;
}

/* 揭示后的文字说明 */
.role-card__info {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.role-card__info-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.role-card__name-sm {
  font-size: 15px;
  font-weight: 700;
  color: var(--role-color, var(--ww-text));
  letter-spacing: 1px;
}

.role-card__skill {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ww-text-dim);
}
</style>
