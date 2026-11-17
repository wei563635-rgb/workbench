<template>
  <Teleport to="body">
    <div v-if="open" class="app-overlay" :class="{ stacked }" @click.self="closeFromBackdrop">
      <section
        ref="panel"
        class="app-dialog-shell"
        :class="`size-${size}`"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
        @keydown="handleKeydown"
      >
        <header class="app-dialog-header">
          <div>
            <h3 :id="titleId">{{ title }}</h3>
            <p v-if="description">{{ description }}</p>
          </div>
          <button class="app-overlay-close" type="button" aria-label="关闭" :disabled="closeDisabled" @click="emit('close')">×</button>
        </header>
        <div class="app-dialog-body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="app-dialog-footer">
          <slot name="footer" />
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { nextTick, onUnmounted, ref, useId, watch } from 'vue'
import { focusFirstElement, getFocusLoopTarget, restoreOverlayFocus } from './overlayFocus.js'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  size: { type: String, default: 'medium' },
  closeOnBackdrop: { type: Boolean, default: false },
  closeDisabled: { type: Boolean, default: false },
  stacked: { type: Boolean, default: false }
})

const emit = defineEmits(['close'])
const titleId = `app-dialog-title-${useId()}`
const panel = ref(null)
let returnFocusTo = null
let focusCycle = 0

function restoreFocus() {
  const target = returnFocusTo
  returnFocusTo = null
  restoreOverlayFocus(target)
}

watch(() => props.open, async (open, wasOpen) => {
  const cycle = ++focusCycle
  if (open && !wasOpen) {
    returnFocusTo = typeof document === 'undefined' ? null : document.activeElement
    await nextTick()
    if (cycle === focusCycle && props.open) focusFirstElement(panel.value)
  } else if (!open && wasOpen) {
    const target = returnFocusTo
    await nextTick()
    if (cycle === focusCycle && !props.open && returnFocusTo === target) restoreFocus()
  }
}, { immediate: true })

onUnmounted(() => {
  focusCycle += 1
  restoreFocus()
})

function handleKeydown(event) {
  if (event.key === 'Escape') {
    if (!props.closeDisabled) {
      event.preventDefault()
      emit('close')
    }
    return
  }
  if (event.key === 'Tab') {
    const target = getFocusLoopTarget(panel.value, document.activeElement, event.shiftKey)
    if (target) {
      event.preventDefault()
      target.focus()
    }
  }
}

function closeFromBackdrop() {
  if (props.closeOnBackdrop && !props.closeDisabled) emit('close')
}
</script>
