import { request, upload, download, fetchBlobUrl, toQuery, newIdempotencyKey } from './http'
import { session } from '../stores/session'

/**
 * 与后端 59 个 @*Mapping 一一对应。
 * 路径前缀 /api/v1 由 http.js 统一加；这里只写资源路径。
 */
export const api = {
  /* ---------------- 健康检查 / 认证 ---------------- */
  health: () => request('/health', { auth: false }),
  login: (payload) => request('/auth/login', { method: 'POST', body: payload, auth: false }),
  // 带上 refreshToken，服务端会一并作废，避免登出后旧刷新令牌仍能换新令牌
  logout: () =>
    request('/auth/logout', { method: 'POST', body: { refreshToken: session.refreshToken || null } }),
  me: () => request('/auth/me'),
  changePassword: (payload) => request('/auth/password', { method: 'PUT', body: payload }),

  /* ---------------- 教师：课程模板与开课 ---------------- */
  courses: (params) => request(`/teacher/courses${toQuery(params)}`),
  createCourse: (payload) => request('/teacher/courses', { method: 'POST', body: payload }),

  teacherOfferings: (params) => request(`/teacher/offerings${toQuery(params)}`),
  createOffering: (payload) =>
    request('/teacher/offerings', { method: 'POST', body: payload, idempotencyKey: newIdempotencyKey() }),
  offering: (offeringId) => request(`/teacher/offerings/${offeringId}`),
  patchOffering: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}`, { method: 'PATCH', body: payload }),
  publishOffering: (offeringId) => request(`/teacher/offerings/${offeringId}/publish`, { method: 'POST' }),
  closeOffering: (offeringId) => request(`/teacher/offerings/${offeringId}/close`, { method: 'POST' }),
  archiveOffering: (offeringId) => request(`/teacher/offerings/${offeringId}/archive`, { method: 'POST' }),
  offeringDashboard: (offeringId) => request(`/teacher/offerings/${offeringId}/dashboard`),

  /* ---------------- 教师：学生名单 ---------------- */
  students: (offeringId, params) => request(`/teacher/offerings/${offeringId}/students${toQuery(params)}`),
  addStudent: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}/students`, { method: 'POST', body: payload }),
  patchStudent: (offeringId, studentId, payload) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}`, { method: 'PATCH', body: payload }),
  deleteStudent: (offeringId, studentId) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}`, { method: 'DELETE' }),
  resetStudentPassword: (offeringId, studentId) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}/reset-password`, { method: 'POST' }),

  /* ---------------- 题目 ---------------- */
  teacherTopics: (offeringId, params) =>
    request(`/teacher/offerings/${offeringId}/topics${toQuery(params)}`),
  createTopic: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}/topics`, { method: 'POST', body: payload }),
  publishTopicsBatch: (offeringId, topicIds) =>
    request(`/teacher/offerings/${offeringId}/topics/publish`, { method: 'POST', body: { topicIds } }),
  topic: (topicId) => request(`/topics/${topicId}`),
  updateTopic: (topicId, payload) => request(`/topics/${topicId}`, { method: 'PUT', body: payload }),
  deleteTopic: (topicId) => request(`/topics/${topicId}`, { method: 'DELETE' }),
  publishTopic: (topicId) => request(`/topics/${topicId}/publish`, { method: 'POST' }),
  archiveTopic: (topicId) => request(`/topics/${topicId}/archive`, { method: 'POST' }),

  /* ---------------- 学生：开课 / 选题 ---------------- */
  studentOfferings: (params) => request(`/student/offerings${toQuery(params)}`),
  studentTopics: (offeringId, params) =>
    request(`/student/offerings/${offeringId}/topics${toQuery(params)}`),
  studentTopicDetail: (offeringId, topicId) =>
    request(`/student/offerings/${offeringId}/topics/${topicId}`),
  selections: (params) => request(`/student/selections${toQuery(params)}`),
  selectionDetail: (selectionId) => request(`/student/selections/${selectionId}`),
  selectTopics: (offeringId, topicIds) =>
    request('/student/selections', {
      method: 'POST',
      body: { offeringId, topicIds },
      idempotencyKey: newIdempotencyKey(),
    }),
  cancelSelection: (selectionId) => request(`/student/selections/${selectionId}`, { method: 'DELETE' }),

  /* ---------------- 提交物 / 文件 ---------------- */
  submissions: (selectionId) => request(`/student/selections/${selectionId}/submissions`),
  submission: (submissionId) => request(`/submissions/${submissionId}`),
  deleteSubmission: (submissionId) => request(`/submissions/${submissionId}`, { method: 'DELETE' }),
  reanalyzeSubmission: (submissionId, force = false) =>
    request(`/submissions/${submissionId}/reanalyze${toQuery({ force })}`, { method: 'POST' }),
  uploadSubmission: (selectionId, type, file, { remark = '', onProgress } = {}) =>
    upload(`/student/selections/${selectionId}/${type === 'SOURCE' ? 'source' : 'report'}`, {
      file,
      query: { remark },
      onProgress,
    }),
  downloadSubmission: (submissionId, filename) =>
    download(`/submissions/${submissionId}/download`, filename),
  uploadFile: (file, purpose = 'TOPIC_ATTACHMENT', { onProgress } = {}) =>
    upload('/files', { file, query: { purpose }, onProgress }),
  downloadFile: (fileId, filename) => download(`/files/${fileId}/download`, filename),

  /* ---------------- 教师：优秀作品模板库 ---------------- */
  excellentWorkCandidates: (offeringId) =>
    request(`/teacher/offerings/${offeringId}/excellent-works/candidates`),
  excellentWorks: (offeringId) => request(`/teacher/offerings/${offeringId}/excellent-works`),
  addExcellentWork: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}/excellent-works`, { method: 'POST', body: payload }),
  deleteExcellentWork: (offeringId, workId) =>
    request(`/teacher/offerings/${offeringId}/excellent-works/${workId}`, { method: 'DELETE' }),
  reindexExcellentWork: (offeringId, workId) =>
    request(`/teacher/offerings/${offeringId}/excellent-works/${workId}/reindex`, { method: 'POST' }),

  /* ---------------- 成绩 ---------------- */
  teacherGrades: (offeringId, params) =>
    request(`/teacher/offerings/${offeringId}/grades${toQuery(params)}`),
  gradeDetail: (gradeId) => request(`/teacher/grades/${gradeId}`),
  adjustGrade: (gradeId, payload) => request(`/teacher/grades/${gradeId}`, { method: 'PATCH', body: payload }),
  unlockGrade: (gradeId) => request(`/teacher/grades/${gradeId}/unlock`, { method: 'POST' }),
  recalculateGrades: (offeringId, payload = {}) =>
    request(`/teacher/offerings/${offeringId}/grades/recalculate`, { method: 'POST', body: payload }),
  publishGrades: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}/grades/publish`, {
      method: 'POST',
      body: payload,
      idempotencyKey: newIdempotencyKey(),
    }),
  unpublishGrades: (offeringId, payload = {}) =>
    request(`/teacher/offerings/${offeringId}/grades/unpublish`, { method: 'POST', body: payload }),
  exportGrades: (offeringId, payload = {}) =>
    request(`/teacher/offerings/${offeringId}/grades/export`, { method: 'POST', body: payload }),
  studentGrades: (offeringId) => request(`/student/grades${toQuery({ offeringId })}`),

  /* ---------------- 答辩（语音转写 + 摄像头在场检测） ---------------- */
  teacherDefenseSessions: (offeringId) =>
    request(`/teacher/defense/sessions${toQuery({ offeringId })}`),
  teacherDefenseCandidates: (offeringId) =>
    request(`/teacher/offerings/${offeringId}/defense/candidates`),
  createDefenseSession: (payload) =>
    request('/teacher/defense/sessions', { method: 'POST', body: payload }),
  defenseSession: (sessionId) => request(`/defense/sessions/${sessionId}`),
  startDefense: (sessionId) => request(`/defense/sessions/${sessionId}/start`, { method: 'POST' }),
  // durationMs 放查询串：upload() 只支持一个文件字段，@RequestParam 同时读取 query 与 multipart 表单。
  uploadDefenseAnswer: (sessionId, questionId, file, durationMs, { onProgress } = {}) =>
    upload(`/defense/sessions/${sessionId}/questions/${questionId}/answer`, {
      file,
      query: { durationMs },
      onProgress,
    }),
  faceCheck: (sessionId, imageBase64) =>
    request(`/defense/sessions/${sessionId}/face-check`, { method: 'POST', body: { imageBase64 } }),
  // 整场答辩录像（webm）：multipart 字段名 file + query durationMs；失败时调用方需保留 blob 以便重试。
  uploadDefenseVideo: (sessionId, video, durationMs, { onProgress } = {}) => {
    let file = video
    if (typeof File !== 'undefined' && video instanceof Blob && !(video instanceof File)) {
      file = new File([video], `defense-${sessionId}.webm`, { type: video.type || 'video/webm' })
    }
    return upload(`/defense/sessions/${sessionId}/video`, { file, query: { durationMs }, onProgress })
  },
  // 录像需要 Authorization 头，不能直接给 <video src>，先 fetch 成 blob URL 再播放。
  defenseVideoBlobUrl: (sessionId) => fetchBlobUrl(`/defense/sessions/${sessionId}/video`),
  finishDefense: (sessionId) => request(`/defense/sessions/${sessionId}/finish`, { method: 'POST' }),
  evaluateDefense: (sessionId) => request(`/defense/sessions/${sessionId}/evaluate`, { method: 'POST' }),
  studentDefense: (selectionId) => request(`/student/defense/selections/${selectionId}`),

  /* ---------------- 通知 / 异步任务 ---------------- */
  notifications: (params) => request(`/notifications${toQuery(params)}`),
  markNotificationRead: (id) => request(`/notifications/${id}/read`, { method: 'PATCH' }),
  readAllNotifications: () => request('/notifications/read-all', { method: 'POST' }),
  task: (taskId) => request(`/tasks/${taskId}`),
  cancelTask: (taskId) => request(`/tasks/${taskId}/cancel`, { method: 'POST' }),
}

export const ROLE_LABEL = { STUDENT: '学生', TEACHER: '教师', ADMIN: '管理员' }
export const DIFFICULTY_LABEL = { EASY: '入门', MEDIUM: '进阶', HARD: '挑战', BASIC: '基础', ADVANCED: '高级' }

/** StudentOfferingVO.cannotSelectReason 由后端返回枚举码，这里翻译成给学生看的句子。 */
export const REASON_LABEL = {
  LIMIT_REACHED: '你已达到本课程的可选题目上限，可先退选再改选',
  NOT_IN_WINDOW: '当前不在选题时间窗口内，请在开放时间内再来',
  CLOSED: '该教学班已关闭选题，仅可查看题目',
}

export function reasonText(code) {
  if (!code) return ''
  return REASON_LABEL[code] || code
}
