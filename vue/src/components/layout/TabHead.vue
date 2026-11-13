<script setup>
/**
 * tab 内页头（比 PageHeader 轻一档）。
 *
 * 详情页每个分区都用同一套结构：页头（标题 + 说明 + 主操作）→ 工具栏 → 数据区 → 分页。
 * 这里只负责页头那一段，不接管任何业务事件；`actions` 插槽里的按钮仍是各 tab 原来的按钮。
 */
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  /** 右上角计数（可选），传 null 时不渲染。 */
  count: { type: [Number, String], default: null },
  countUnit: { type: String, default: '' },
})
</script>

<template>
  <div class="tab-head">
    <div class="tab-head__main">
      <h3 class="tab-head__title">
        {{ title }}
        <span v-if="count !== null && count !== ''" class="tab-head__count num">{{ count }}{{ countUnit }}</span>
      </h3>
      <p v-if="description || $slots.description" class="tab-head__desc">
        <slot name="description">{{ description }}</slot>
      </p>
      <div v-if="$slots.meta" class="tab-head__meta">
        <slot name="meta" />
      </div>
    </div>
    <div v-if="$slots.actions" class="tab-head__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.tab-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}

.tab-head__main {
  min-width: 0;
}

.tab-head__title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.1px;
}

.tab-head__count {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-3);
}

.tab-head__desc {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--text-2);
}

.tab-head__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.tab-head__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: auto;
}
</style>
