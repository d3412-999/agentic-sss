/**
 * 侧边栏与命令面板共用的导航配置（单一事实来源）。
 *
 * - `group` 的先后顺序就是侧边栏的分组顺序，命令面板按同样顺序拍平。
 * - `roles` 的语义与路由 meta.roles 一致：空数组/缺省表示登录即可见。
 * - `badge` 只给侧边栏使用（tasks=进行中任务数，unread=未读通知数）。
 * - `paletteLabel` 是命令面板里更短的叫法（历史文案），缺省时用 `label`。
 */
export const NAV_GROUPS = [
  {
    group: '工作台',
    items: [
      { to: '/', label: '概览', icon: 'home', roles: ['TEACHER', 'STUDENT', 'ADMIN'] },
      { to: '/offerings', label: '教学班', icon: 'book', roles: ['TEACHER'] },
      { to: '/courses', label: '我的课程', icon: 'book', roles: ['STUDENT'] },
      { to: '/selections', label: '我的选题', icon: 'target', roles: ['STUDENT'] },
      {
        to: '/grades',
        label: '成绩中心',
        paletteLabel: '成绩',
        icon: 'chart',
        roles: ['TEACHER', 'STUDENT'],
      },
    ],
  },
  {
    group: '协作',
    items: [
      {
        to: '/tasks',
        label: '任务中心',
        icon: 'clock',
        roles: ['TEACHER', 'STUDENT', 'ADMIN'],
        badge: 'tasks',
      },
      {
        to: '/notifications',
        label: '站内通知',
        icon: 'bell',
        roles: ['TEACHER', 'STUDENT', 'ADMIN'],
        badge: 'unread',
      },
    ],
  },
  {
    group: '个人',
    items: [{ to: '/settings', label: '设置', icon: 'gear', roles: ['TEACHER', 'STUDENT', 'ADMIN'] }],
  },
]

/** 侧边栏：按角色过滤条目，没有可见条目的分组整组隐藏。 */
export function navGroupsForRole(role) {
  return NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.roles.includes(role)),
  })).filter((group) => group.items.length > 0)
}

/** 命令面板：按侧边栏的分组顺序拍平后的可访问条目。 */
export function navItemsForRole(role) {
  return NAV_GROUPS.flatMap((group) => group.items).filter((item) => item.roles.includes(role))
}
