<template>
  <div class="admin-pagination">
    <span v-if="page.loading" class="admin-pagination-status" role="status" aria-live="polite">加载中…</span>
    <ElPagination
      background
      layout="total, prev, pager, next"
      :current-page="page.page || 1"
      :page-size="page.pageSize || 10"
      :total="page.total || 0"
      :disabled="page.loading"
      @current-change="$emit('change', $event)"
    />
  </div>
</template>

<script setup>
import { ElPagination } from 'element-plus'

defineProps({ page: { type: Object, required: true } })
defineEmits(['change'])
</script>
