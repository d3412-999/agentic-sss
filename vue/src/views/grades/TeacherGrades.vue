<script setup>
/**
 * 教师端成绩中心：选教学班 → 重算 / 调整 / 发布。
 * 实际表格与发布逻辑仍在 GradesTab 内，这里只负责「选班」这一层。
 */
import { onMounted, ref, watch } from 'vue'
import PageHeader from '../../components/layout/PageHeader.vue'
import EmptyState from '../../components/EmptyState.vue'
import GradesTab from '../../components/offering/GradesTab.vue'
import { api } from '../../api/endpoints'
import { toastError } from '../../stores/toast'

const loading = ref(true)
const offerings = ref([])
const offeringId = ref('')
const currentOffering = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await api.teacherOfferings({ page: 1, size: 50 })
    offerings.value = data?.list || []
    if (!offeringId.value && offerings.value.length) offeringId.value = offerings.value[0].id
    await loadDetail()
  } catch (err) {
    toastError(err, '加载教学班失败')
  } finally {
    loading.value = false
  }
}

async function loadDetail() {
  if (!offeringId.value) return
  try {
    currentOffering.value = await api.offering(offeringId.value)
  } catch {
    currentOffering.value = null
  }
}

onMounted(load)
watch(offeringId, loadDetail)
</script>

<template>
  <div>
    <PageHeader title="成绩中心" description="重算 → 调整 → 发布。发布后成绩锁定，撤销仅限 24 小时内。">
      <template #actions>
        <select v-model="offeringId" class="select" style="width: 260px" :disabled="!offerings.length">
          <option v-for="o in offerings" :key="o.id" :value="o.id">
            {{ o.courseName }} · {{ o.className }} · {{ o.semester }}
          </option>
        </select>
      </template>
    </PageHeader>

    <div v-if="loading" class="skeleton" style="height: 220px" />
    <EmptyState v-else-if="!offerings.length" icon="book" title="还没有教学班" hint="先创建教学班并添加学生" />
    <GradesTab v-else-if="offeringId" :key="offeringId" :offering-id="offeringId" :offering="currentOffering" />
  </div>
</template>
