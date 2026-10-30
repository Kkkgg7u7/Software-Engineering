import type { RoleCounts, RoleMeta } from '@/types'

export function roleTotal(counts: RoleCounts): number {
  return Object.values(counts).reduce((total, count) => total + count, 0)
}

export function roleConfigurationError(counts: RoleCounts, players: number, roles: RoleMeta[]): string {
  if (!roles.length) return '身份规则加载中，请稍候'
  for (const role of roles) {
    const count = counts[role.name] ?? 0
    if (!Number.isInteger(count) || count < 0 || count > Math.min(players, role.maxCount))
      return `${role.label}人数应为 0～${Math.min(players, role.maxCount)}`
  }
  const total = roleTotal(counts)
  if (total !== players) return `已配置 ${total} / ${players} 人，${total < players ? '还差' : '超出'} ${Math.abs(total - players)} 人`
  const wolves = (counts.WEREWOLF ?? 0) + (counts.WHITE_WOLF_KING ?? 0)
  if (!wolves) return '至少需要 1 名狼人阵营玩家（狼人或白狼王）'
  if (wolves === total) return '至少需要 1 名好人阵营玩家'
  if (wolves >= total - wolves) return '狼人阵营人数必须少于好人，否则按现有胜负规则开局即获胜'
  return ''
}

export function sameRoles(a: RoleCounts, b: RoleCounts): boolean {
  return Object.keys({...a, ...b}).every(key => (a[key as keyof RoleCounts] ?? 0) === (b[key as keyof RoleCounts] ?? 0))
}
