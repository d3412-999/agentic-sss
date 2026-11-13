import { session } from '../stores/session.js'

/**
 * 当前登录用户 id（本地存储的隔离维度）。
 *
 * 取不到时退回 `anon`：宁可让所有匿名的数据共用一份，也不要把 A 的草稿写到 B 名下。
 * 注意只在本地存储的 key 上用，不做任何鉴权判断——后端仍然按令牌认人。
 */
export function currentUserId() {
  return session.me?.id || session.user?.id || session.user?.username || 'anon'
}

/** 当前角色，纯粹给「要不要给学生看这一块」这类 UI 判断用。 */
export function currentRole() {
  return session.user?.role || session.me?.role || ''
}
