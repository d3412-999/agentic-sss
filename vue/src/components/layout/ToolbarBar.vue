<script setup>
/**
 * 列表页统一工具栏：左侧筛选 / 右侧搜索与操作。
 * 窄屏自动换行，不改变任何控件的原有行为。
 */
</script>

<template>
  <div class="toolbar">
    <div class="toolbar__left">
      <slot name="left" />
    </div>
    <div class="toolbar__right">
      <slot name="right" />
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
}

.toolbar__left,
.toolbar__right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.toolbar__right {
  margin-left: auto;
}
</style>
