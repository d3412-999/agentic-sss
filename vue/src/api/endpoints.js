import { request, toQuery, newIdempotencyKey } from './http'

// 路径与后端 59 个 @*Mapping 逐一对应；完整清单见 README「接口概览」与 Swagger。
export const api = {
  // 健康检查：SecurityConfig permitAll，无需 token
  health: () => request('/health', { auth: false }),

  // 认证
  login: (payload) => request('/auth/login', { method: 'POST', body: payload, auth: false }),
  logout: () => request('/auth/logout', { method: 'POST' }),
  me: () => request('/auth/me'),
  changePassword: (payload) => request('/auth/password', { method: 'PUT', body: payload }),

  // 学生端
  studentOfferings: (params) => request(`/student/offerings${toQuery(params)}`),
  studentTopics: (offeringId, params) =>
    request(`/student/offerings/${offeringId}/topics${toQuery(params)}`),
  studentTopicDetail: (offeringId, topicId) =>
    request(`/student/offerings/${offeringId}/topics/${topicId}`),
  mySelections: (params) => request(`/student/selections${toQuery(params)}`),
  selectTopics: (offeringId, topicIds) =>
    request('/student/selections', {
      method: 'POST',
      body: { offeringId, topicIds },
      idempotencyKey: newIdempotencyKey(),
    }),
  cancelSelection: (selectionId) =>
    request(`/student/selections/${selectionId}`, { method: 'DELETE' }),

  // 教师端
  teacherOfferings: (params) => request(`/teacher/offerings${toQuery(params)}`),
  teacherTopics: (offeringId, params) =>
    request(`/teacher/offerings/${offeringId}/topics${toQuery(params)}`),
  offeringDashboard: (offeringId) => request(`/teacher/offerings/${offeringId}/dashboard`),
  teacherGrades: (offeringId, params) =>
    request(`/teacher/offerings/${offeringId}/grades${toQuery(params)}`),

  // 通知与异步任务
  notifications: (params) => request(`/notifications${toQuery(params)}`),
  markNotificationRead: (id) => request(`/notifications/${id}/read`, { method: 'PATCH' }),
  readAllNotifications: () => request('/notifications/read-all', { method: 'POST' }),
  task: (taskId) => request(`/tasks/${taskId}`),
}
