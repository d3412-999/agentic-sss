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
  /** 教师端只读：某学生在本开课下的选题，用于「提交评审」定位 selectionId。 */
  studentSelectionsOfOffering: (offeringId, studentId) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}/selections`),
  /**
   * 教师端只读：某选题的提交物列表。
   * 不能复用 student 版 `/student/selections/{id}/submissions`——那条路径在
   * SecurityConfig 里限定 ROLE_STUDENT，教师调用会在进入 Service 前就被 403。
   */
  teacherSubmissions: (selectionId) => request(`/teacher/selections/${selectionId}/submissions`),
  addStudent: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}/students`, { method: 'POST', body: payload }),
  patchStudent: (offeringId, studentId, payload) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}`, { method: 'PATCH', body: payload }),
  deleteStudent: (offeringId, studentId) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}`, { method: 'DELETE' }),
  // 重置为初始密码（学生默认是学号）；接口不回传密码，前端也不展示
  resetStudentPassword: (offeringId, studentId) =>
    request(`/teacher/offerings/${offeringId}/students/${studentId}/reset-password`, { method: 'POST' }),

  /* ---------------- 教师：学生名单批量导入 / 导出 ---------------- */
  // 导入限额（扩展名 / 大小 / 条数）与后端校验共用同一份配置
  studentImportPolicy: () => request('/meta/student-import-policy', { auth: false }),
  parseStudentText: (offeringId, text) =>
    request(`/teacher/offerings/${offeringId}/students/import/parse-text`, { method: 'POST', body: { text } }),
  // 解析文件：只读预览，不写库；文件走 multipart，能拿到上传进度
  parseStudentFile: (offeringId, file, { onProgress } = {}) =>
    upload(`/teacher/offerings/${offeringId}/students/import/parse-file`, { file, onProgress }),
  confirmStudentImport: (offeringId, rows) =>
    request(`/teacher/offerings/${offeringId}/students/import/confirm`, {
      method: 'POST',
      body: { rows },
      idempotencyKey: newIdempotencyKey(),
    }),
  downloadStudentImportTemplate: (offeringId, filename = '学生名单导入样例.xlsx') =>
    download(`/teacher/offerings/${offeringId}/students/import/template`, filename),
  exportStudents: (offeringId, filename = '学生名单.csv') =>
    download(`/teacher/offerings/${offeringId}/students/export`, filename),
  batchRemoveStudents: (offeringId, studentIds) =>
    request(`/teacher/offerings/${offeringId}/students/batch-remove`, {
      method: 'POST',
      body: { studentIds },
    }),

  /* ---------------- 题目 ---------------- */
  teacherTopics: (offeringId, params) =>
    request(`/teacher/offerings/${offeringId}/topics${toQuery(params)}`),
  createTopic: (offeringId, payload) =>
    request(`/teacher/offerings/${offeringId}/topics`, { method: 'POST', body: payload }),
  topic: (topicId) => request(`/topics/${topicId}`),
  updateTopic: (topicId, payload) => request(`/topics/${topicId}`, { method: 'PUT', body: payload }),
  deleteTopic: (topicId) => request(`/topics/${topicId}`, { method: 'DELETE' }),
  // 附件：先上传拿 fileId，再挂到题目上；编辑时用增/删/排序三个独立动作
  addTopicAttachments: (topicId, fileIds) =>
    request(`/topics/${topicId}/attachments`, { method: 'POST', body: { fileIds } }),
  removeTopicAttachment: (topicId, fileId) =>
    request(`/topics/${topicId}/attachments/${fileId}`, { method: 'DELETE' }),
  reorderTopicAttachments: (topicId, fileIds) =>
    request(`/topics/${topicId}/attachments/order`, { method: 'PUT', body: { fileIds } }),

  /* ---------------- 字典 / 上传策略 ---------------- */
  difficulties: () => request('/meta/difficulties'),
  uploadPolicy: () => request('/meta/upload-policy'),

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
  /**
   * 两阶段提交第一步：单文件落盘换 fileId。
   *
   * 一文件一请求，才能做到「每个文件独立进度、独立重试、独立取消」——
   * 一次 multipart 传 9 个文件只有一个总进度，失败时也定位不到是哪一个坏。
   */
  uploadSubmissionFile: (selectionId, type, file, { onProgress, signal } = {}) =>
    upload(`/student/selections/${selectionId}/files`, { file, query: { type }, onProgress, signal }),
  /**
   * 文件元数据（只读）。
   * 恢复本地「已上传但未提交」列表时逐条回查：`exists=false` 表示记录已失效，
   * 前端应把它从本地列表剔除，而不是继续展示一个点下载就 404 的幽灵项。
   */
  fileInfo: (fileId) => request(`/files/${fileId}`),
  /** 两阶段提交第二步：把 fileId 列表绑定成一次提交（生成新版本并触发评审）。 */
  bindSubmissionFiles: (selectionId, body) =>
    request(`/student/selections/${selectionId}/submissions`, { method: 'POST', body }),
  deleteSubmissionFile: (submissionId, versionFileId) =>
    request(`/submissions/${submissionId}/files/${versionFileId}`, { method: 'DELETE' }),
  /** 调整同一版本内的文件顺序，body 为 versionFileId 的有序数组。 */
  reorderSubmissionFiles: (submissionId, versionFileIds) =>
    request(`/submissions/${submissionId}/files/order`, { method: 'PUT', body: versionFileIds }),
  downloadSubmissionFile: (submissionId, versionFileId, filename) =>
    download(`/submissions/${submissionId}/files/${versionFileId}/download`, filename),
  /** 预览用：带鉴权拉成 blob URL。inline 只对 PDF 生效，其它类型后端仍按 attachment 返回。 */
  submissionFileBlobUrl: (submissionId, versionFileId) =>
    fetchBlobUrl(`/submissions/${submissionId}/files/${versionFileId}/download?inline=true`),
  /** 版本历史（只读）。历史版本用于回看，不能新增或改版本号。 */
  submissionVersions: (submissionId) => request(`/submissions/${submissionId}/versions`),
  submissionDetail: (submissionId, versionId) =>
    request(`/submissions/${submissionId}${toQuery({ versionId })}`),
  submissionSimilarities: (submissionId) => request(`/submissions/${submissionId}/similarities`),
  submissionPolicy: () => request('/meta/submission-policy'),
  downloadSubmission: (submissionId, filename) =>
    download(`/submissions/${submissionId}/download`, filename),
  uploadFile: (file, purpose = 'TOPIC_ATTACHMENT', { onProgress } = {}) =>
    upload('/files', { file, query: { purpose }, onProgress }),
  /**
   * 题目附件上传（单文件一次请求）。
   *
   * 后端 /files/batch 支持一次 multipart 传多个文件，但浏览器只有一个上传进度事件——
   * 要「每个文件独立进度、独立重试、独立取消」，必须一文件一请求。这里固定走批量端点
   * 的单文件形态：契约不变，进度粒度足够。
   */
  uploadTopicFile: (file, { onProgress, signal } = {}) =>
    upload('/files/batch', { files: [file], fieldName: 'files', onProgress, signal }),
  /** 真批量：一次请求传多个文件，只有一个总进度（调用方不需要逐文件进度时用）。 */
  uploadTopicFiles: (files, { onProgress } = {}) =>
    upload('/files/batch', { files, fieldName: 'files', onProgress }),
  downloadFile: (fileId, filename) => download(`/files/${fileId}/download`, filename),
  // PDF 内嵌预览：<iframe src> 带不了 Authorization 头，先拉成 blob URL 再塞给 iframe
  topicAttachmentBlobUrl: (fileId) => fetchBlobUrl(`/files/${fileId}/download?inline=1`),

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
  /**
   * 答辩期间只读查看本人源码：后端流式解压后只回纯文本，不存在「下载 zip」这条路径。
   * 失败一律由后端权限链给出 403 / 422，前端不做任何兜底放宽。
   */
  defenseSourceCode: (sessionId) => request(`/defense/sessions/${sessionId}/source-code`),
  /** 前端辅助检测上报（仅作证据）：type 只允许 TAB_SWITCH / DEVTOOLS / PASTE_PATTERN / COPY_BLOCK / SHORTCUT_BLOCK。 */
  reportDefenseViolation: (sessionId, type, detail = {}) =>
    request(`/defense/sessions/${sessionId}/violation`, {
      method: 'POST',
      body: { type, detail },
    }),
  /** 教师端违规汇总（含证据、当前计数、会话状态）。 */
  teacherDefenseViolations: (offeringId) =>
    request(`/teacher/offerings/${offeringId}/defense/violations`),
  /** 教师确认误报：恢复答辩（计数重新开始）。 */
  resumeDefense: (sessionId) =>
    request(`/teacher/defense/sessions/${sessionId}/resume`, { method: 'POST' }),
  /** 教师确认违规：维持终止，会话转到 COMPLETED，成绩留空待评分。 */
  upholdDefense: (sessionId) =>
    request(`/teacher/defense/sessions/${sessionId}/uphold`, { method: 'POST' }),

  /* ---------------- 答辩：统一评审状态 / 题数规则 / 三原则 ---------------- */
  /**
   * 源码 + 报告的统一评审状态（按提交物 ID 查询，返回同选题下两侧状态）。
   * 前端只认 finished 判断是否停止轮询，不再靠反复拉详情猜进度。
   */
  reviewStatus: (submissionId, options) =>
    request(`/submissions/${submissionId}/review-status`, options),
  /** 题数分档 / 三原则阈值 / 加分题配置：发起前预览与规则说明共用。 */
  defenseQuestionRule: () => request('/teacher/defense/question-rule'),
  /** 批量发起答辩：受理后立即返回 batchId，需要轮询 detail 拿逐条结果。 */
  batchDefenseSessions: (payload) =>
    request('/teacher/defenses/batch', { method: 'POST', body: payload }),
  batchDefenseDetail: (batchId) => request(`/teacher/defenses/batch/${batchId}`),
  /** 教师覆盖「完全依赖 AI」判定：理由必填，仅留痕。 */
  overrideDefenseAiDependent: (sessionId, payload) =>
    request(`/teacher/defense/sessions/${sessionId}/ai-dependent-override`, {
      method: 'POST',
      body: payload,
    }),
  /** 跳过加分题（只有加分题可跳过，计分题不允许逃题）。 */
  skipDefenseBonus: (sessionId, questionId) =>
    request(`/defense/sessions/${sessionId}/questions/${questionId}/skip`, { method: 'POST' }),

  /* ---------------- 管理员：教师账号与权限 ---------------- */
  adminTeachers: (params) => request(`/admin/teachers${toQuery(params)}`),
  adminTeacherPermissions: () => request('/admin/teacher-permissions'),
  createTeacher: (payload) => request('/admin/teachers', { method: 'POST', body: payload }),
  createTeachersBatch: (teachers) =>
    request('/admin/teachers/batch', { method: 'POST', body: { teachers } }),
  updateTeacher: (teacherId, payload) =>
    request(`/admin/teachers/${teacherId}`, { method: 'PATCH', body: payload }),
  updateTeacherStatus: (teacherId, status) =>
    request(`/admin/teachers/${teacherId}/status`, { method: 'PATCH', body: { status } }),
  resetTeacherPassword: (teacherId) =>
    request(`/admin/teachers/${teacherId}/reset-password`, { method: 'POST' }),
  deleteTeacher: (teacherId) =>
    request(`/admin/teachers/${teacherId}`, { method: 'DELETE' }),

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
