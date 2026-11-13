<script setup>
/**
 * 统一页面头部：面包屑 / 标题 / 说明 / 附加信息 / 步骤条 / 右侧操作。
 *
 * 只负责布局，不承载业务逻辑；样式复用全局 `.page-head` 与其 token。
 * `compact` 用于需要与内容一起吸顶的详情页：标题降一档、说明行隐藏，
 * 避免吸顶区占掉过多纵向空间（1366×768 下尤其明显）。
 */
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  compact: { type: Boolean, default: false },
})
</script>

<template>
  <header class="page-head" :class="{ 'page-head--compact': compact }">
    <div class="page-head__main">
      <div v-if="$slots.breadcrumb" class="page-head__crumb small dim">
        <slot name="breadcrumb" />
      </div>
      <h1 class="page-head__title">
        <slot name="title">{{ title }}</slot>
      </h1>
      <p v-if="!compact && (description || $slots.description)" class="page-head__desc">
        <slot name="description">{{ description }}</slot>
      </p>
      <div v-if="$slots.meta" class="page-head__meta">
        <slot name="meta" />
      </div>
      <div v-if="$slots.steps" class="page-head__steps">
        <slot name="steps" />
      </div>
    </div>
    <div v-if="$slots.actions" class="page-head__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-head__main {
  min-width: 0;
}

.page-head__crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.page-head__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}

.page-head__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.page-head__steps {
  margin-top: 12px;
  overflow-x: auto;
  padding-bottom: 2px;
}

/* 吸顶详情页用：标题降一档，去掉多余的上间距 */
.page-head--compact {
  margin-bottom: 10px;
}

.page-head--compact :deep(h1) {
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.2px;
}

.page-head--compact .page-head__meta {
  margin-top: 6px;
}
</style>
