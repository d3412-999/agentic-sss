/**
 * 页面级「动线」配置（纯展示，不参与任何业务判断）。
 *
 * 学生端的顺序刻意按任务推进来排，而不是按数据表来排：
 * 进入课程 → 选题 → 提交材料 → AI 评审 → 答辩 → 成绩。
 *
 * `to` 只是给学生一个可回看的入口；答辩需要 sessionId，无法给出稳定路径，
 * 因此留空（渲染成不可点的纯文本）。这里不含任何权限或状态判断。
 */
export const STUDENT_FLOW = [
  { key: 'course', label: '进入课程', to: '/courses' },
  // 选题必须挂在某个教学班下（/courses/:offeringId/topics），所以回到课程列表挑选
  { key: 'topic', label: '选题', to: '/courses' },
  { key: 'submit', label: '提交材料', to: '/selections' },
  { key: 'review', label: 'AI 评审', to: '/selections' },
  { key: 'defense', label: '答辩', to: null },
  { key: 'grade', label: '成绩', to: '/grades' },
]
