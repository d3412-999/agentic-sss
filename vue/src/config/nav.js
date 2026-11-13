/**
 * 一级导航（功能集块）配置：单一事实来源，供侧栏与命令面板共用。
 *
 * 结构：`{ group, icon, children: [{ to, label, badge? }] }`
 * - `group` 是左侧一级栏的集块名；`icon` 是集块图标，折叠态只显示它。
 * - `children` 是集块展开后的子功能，点击即跳转；只有一个子项时集块直接当入口用。
 * - 教师端与学生端各自维护一棵完整的树，条目归属由所在树决定，不再靠 roles 过滤拼装。
 *
 * 注意：`to` 与路由表一一对应，这里只决定「在哪一端、以什么层级露出」。
 */

export const TEACHER_NAV = [
  {
    group: '教学班',
    icon: 'book',
    children: [{ to: '/offerings', label: '我的教学班' }],
  },
  {
    group: '成绩',
    icon: 'chart',
    children: [{ to: '/grades', label: '成绩中心' }],
  },
  {
    group: '协作',
    icon: 'clock',
    children: [
      { to: '/tasks', label: '任务中心', badge: 'tasks' },
      { to: '/notifications', label: '站内通知', badge: 'unread' },
    ],
  },
  {
    group: '个人',
    icon: 'gear',
    children: [{ to: '/settings', label: '账号设置' }],
  },
]

export const STUDENT_NAV = [
  {
    group: '我的学习',
    icon: 'book',
    children: [
      { to: '/courses', label: '我的课程' },
      { to: '/selections', label: '我的选题' },
    ],
  },
  {
    group: '我的成绩',
    icon: 'chart',
    children: [{ to: '/grades', label: '成绩中心' }],
  },
  {
    group: '协作',
    icon: 'clock',
    children: [
      { to: '/tasks', label: '任务中心', badge: 'tasks' },
      { to: '/notifications', label: '站内通知', badge: 'unread' },
    ],
  },
  {
    group: '个人',
    icon: 'gear',
    children: [{ to: '/settings', label: '账号设置' }],
  },
]

/** 管理员平台只服务一个用途：教师账号与权限管理。 */
export const ADMIN_NAV = [
  {
    group: '平台',
    icon: 'users',
    children: [{ to: '/admin/teachers', label: '教师账号' }],
  },
]

const NAV_BY_ROLE = {
  TEACHER: TEACHER_NAV,
  STUDENT: STUDENT_NAV,
  ADMIN: ADMIN_NAV,
}

/** 侧栏：该角色自己的导航树。 */
export function navGroupsForRole(role) {
  return NAV_BY_ROLE[role] || []
}

/** 命令面板：按一级栏顺序拍平，子项继承集块图标。 */
export function navItemsForRole(role) {
  return navGroupsForRole(role).flatMap((group) =>
    group.children.map((child) => ({ ...child, icon: child.icon || group.icon, group: group.group })),
  )
}

/**
 * 当前路径命中哪个集块，只用于高亮与自动展开，不参与任何路由判断。
 * `''` 表示「根路径」，即集块自身的落地地址（如 `/offerings/:id` 命中 `/offerings`）。
 */
export function activeGroupFor(role, path) {
  const groups = navGroupsForRole(role)
  return (
    groups.find((group) =>
      group.children.some((child) =>
        child.to === '/' ? path === '/' : path === child.to || path.startsWith(`${child.to}/`),
      ),
    ) || null
  )
}
