<template>
  <ElSelect
    class="ui-select"
    :model-value="modelValue"
    :placeholder="placeholder"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <ElOption
      v-for="option in options"
      :key="option.value"
      :label="option.label"
      :value="option.value"
      :disabled="option.disabled"
    />
  </ElSelect>
</template>

<script setup>
import { ElOption, ElSelect } from 'element-plus'

defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '' }
})

defineEmits(['update:modelValue'])
</script>
