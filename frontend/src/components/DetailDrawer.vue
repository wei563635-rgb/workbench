<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="app-overlay detail-drawer-overlay"
      :class="{ stacked }"
      @click.self="emit('close')"
    >
      <aside
        ref="panel"
        class="detail-drawer-shell"
        :class="{ wide, 'extra-wide': extraWide }"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
        @keydown="handleKeydown"
      >
        <header class="detail-drawer-header">
          <div>
            <h3 :id="titleId">{{ title }}</h3>
            <p v-if="description">{{ description }}</p>
          </div>
          <button class="app-overlay-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
        </header>
        <div class="detail-drawer-body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="detail-drawer-footer">
          <slot name="footer" />
        </footer>
      </aside>
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
  wide: { type: Boolean, default: false },
  extraWide: { type: Boolean, default: false },
  stacked: { type: Boolean, default: false }
})

const emit = defineEmits(['close'])
const titleId = `detail-drawer-title-${useId()}`
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
    event.preventDefault()
    emit('close')
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
</script>
