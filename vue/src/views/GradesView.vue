<script setup>
/**
 * 成绩中心路由的薄壳：按角色选择教师端 / 学生端成绩视图。
 *
 * 教师端 → views/grades/TeacherGrades.vue（选班 + 重算发布）
 * 学生端 → views/grades/StudentGrades.vue（只读成绩卡）
 *
 * 路由路径 `/grades` 与 meta.roles 均未改动。
 */
import { computed } from 'vue'
import TeacherGrades from './grades/TeacherGrades.vue'
import StudentGrades from './grades/StudentGrades.vue'
import { currentRole } from '../stores/session'

const isTeacher = computed(() => currentRole.value === 'TEACHER')
</script>

<template>
  <component :is="isTeacher ? TeacherGrades : StudentGrades" />
</template>
