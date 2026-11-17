<template>
  <ElCheckbox
    class="ui-check"
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <slot />
  </ElCheckbox>
</template>

<script setup>
import { ElCheckbox } from 'element-plus'

defineProps({ modelValue: { type: Boolean, default: false } })
defineEmits(['update:modelValue'])
</script>
