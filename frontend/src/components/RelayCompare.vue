<template>
  <div class="relay-compare-overlay" @click.self="$emit('close')">
    <section class="relay-compare-drawer" role="dialog" aria-modal="true" aria-label="员工 Token 消耗横向对比">
      <header class="relay-compare-header">
        <div>
          <h2>员工 Token 消耗横向对比</h2>
          <small>统计周期：{{ range.from }} 至 {{ range.to }}</small>
        </div>
        <div class="relay-date-controls">
          <div class="relay-periods">
            <button v-for="item in presets" :key="item.value" :class="{ selected: preset === item.value }" @click="setPreset(item.value)">
              {{ item.label }}
            </button>
          </div>
          <div class="relay-date-picker">
            <input v-model="draft.from" type="date" aria-label="对比开始日期" />
            至
            <input v-model="draft.to" type="date" aria-label="对比结束日期" />
            <button @click="applyRange">查询</button>
          </div>
          <button class="btn-secondary" aria-label="关闭对比" @click="$emit('close')">关闭</button>
        </div>
      </header>

      <div class="relay-compare-content">
        <p v-if="error" class="relay-error" role="alert">{{ error }}</p>
        <p v-if="loading" role="status">正在加载对比数据…</p>
        <div class="relay-compare-members">
          <strong>当前对比员工（{{ selected.length }} / 8）</strong>
          <span v-for="person in selected" :key="person.id">
            {{ person.name }}
            <button v-if="selected.length > 2" :aria-label="`移除${person.name}`" @click="remove(person.id)">×</button>
          </span>
          <label>
            <input v-model="candidateSearch" type="search" placeholder="输入员工姓名搜索添加"
              aria-label="搜索添加对比员工" :disabled="selected.length >= 8" />
          </label>
          <div v-if="candidateSearch" class="relay-compare-suggestions">
            <button v-for="person in candidates" :key="person.id"
              :disabled="selected.length >= 8 || selected.some(item => item.id === person.id)" @click="add(person)">
              {{ person.name }} · {{ person.departmentName }}
            </button>
            <small v-if="!candidates.length">没有匹配的员工</small>
          </div>
        </div>

        <template v-if="rows.length">
          <section class="relay-compare-section">
            <h3>核心消耗</h3>
            <p class="relay-caption">比较同一周期的消耗；颜色表示与组内均值的关系。本月额度按当前自然月显示。</p>
            <div class="relay-table-scroll">
              <table>
                <thead><tr>
                  <th>指标</th>
                  <th v-for="person in rows" :key="person.id">{{ person.name }}<small>{{ person.departmentName }}</small></th>
                </tr></thead>
                <tbody>
                  <tr v-for="metric in metrics" :key="metric.key">
                    <th>{{ metric.label }}</th>
                    <td v-for="person in rows" :key="person.id" :class="tone(person[metric.key], metric.key)">
                      {{ metric.key === 'totalTokens' ? tokens(person[metric.key]) : (person[metric.key] ?? 0) }}{{ metric.unit }}
                    </td>
                  </tr>
                  <tr><th>输入 / 输出 Token</th><td v-for="person in rows" :key="person.id">{{ tokens(person.inputTokens) }} / {{ tokens(person.outputTokens) }}</td></tr>
                  <tr><th>单次平均消耗</th><td v-for="person in rows" :key="person.id">{{ tokens(person.requests ? person.totalTokens / person.requests : 0) }} Tokens</td></tr>
                  <tr><th>本月额度进度</th><td v-for="person in rows" :key="person.id">{{ tokens(person.used) }} / {{ tokens(person.personalLimit) }} Tokens · {{ percent(person.used, person.personalLimit).toFixed(1) }}%</td></tr>
                </tbody>
              </table>
            </div>
          </section>

          <div class="relay-compare-grid">
            <section class="relay-compare-section">
              <h3>Token 消耗趋势对比</h3>
              <div class="relay-compare-chart">
                <div v-for="day in days" :key="day" class="relay-compare-day">
                  <div class="relay-compare-bars">
                    <span v-for="(person, index) in rows" :key="person.id"
                      :style="{ height: barHeight(value(person, day, 'tokens'), tokenMax), background: colors[index % colors.length] }"
                      :title="`${person.name} · ${day}：${tokens(value(person, day, 'tokens'))} Tokens`" />
                  </div>
                  <small>{{ day.slice(5) }}</small>
                </div>
              </div>
              <p v-if="!tokenMax" class="relay-empty">暂无 Token 用量</p>
            </section>
            <section class="relay-compare-section">
              <h3>图片生成张数趋势对比</h3>
              <div class="relay-compare-chart">
                <div v-for="day in days" :key="day" class="relay-compare-day">
                  <div class="relay-compare-bars">
                    <span v-for="(person, index) in rows" :key="person.id"
                      :style="{ height: barHeight(value(person, day, 'images'), imageMax), background: colors[index % colors.length] }"
                      :title="`${person.name} · ${day}：${value(person, day, 'images')} 张`" />
                  </div>
                  <small>{{ day.slice(5) }}</small>
                </div>
              </div>
              <p v-if="!imageMax" class="relay-empty">暂无图片用量</p>
            </section>
          </div>

          <section class="relay-compare-section">
            <h3>AI 工具使用分布对比</h3>
            <div class="relay-compare-tools">
              <div v-for="person in rows" :key="person.id">
                <strong>{{ person.name }}</strong>
                <div class="relay-compare-tool-track">
                  <span v-for="(tool, index) in person.tools" :key="tool.key"
                    :style="{ width: `${sharePercent(tool.tokens, person.totalTokens)}%`, background: colors[index % colors.length] }"
                    :title="`${tool.key}：${tokens(tool.tokens)} Tokens`" />
                </div>
                <small>{{ person.tools.map(tool => `${tool.key} ${tokens(tool.tokens)}`).join(' · ') || '暂无工具用量' }}</small>
              </div>
            </div>
          </section>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getJson } from '../api.js'
import { rangeFor, tokens, percent, sharePercent, readableRelayError } from '../relayView.js'

const props = defineProps({ selected: { type: Array, required: true }, initialRange: { type: Object, required: true } })
const emit = defineEmits(['close', 'update:selected'])
const presets = [{ label: '今日实时', value: 'today' }, { label: '近 7 天', value: 'week' }, { label: '本月累计', value: 'month' }]
const metrics = [{ key: 'totalTokens', label: 'Token 消耗', unit: ' Tokens' }, { key: 'requests', label: '调用次数', unit: ' 次' }, { key: 'imageCount', label: '生成图片', unit: ' 张' }]
const colors = ['#5b55ea', '#ec4899', '#10b981', '#f59e0b', '#3b82f6', '#8b5cf6']
const range = ref({ ...props.initialRange })
const draft = ref({ ...props.initialRange })
const preset = ref('')
const rows = ref([])
const loading = ref(false)
const error = ref('')
const candidateSearch = ref('')
const candidates = ref([])
let request = 0
let candidateRequest = 0

const days = computed(() => {
  const all = []
  const day = new Date(`${range.value.from}T00:00:00Z`)
  const end = new Date(`${range.value.to}T00:00:00Z`)
  while (day <= end && all.length <= 366) {
    all.push(day.toISOString().slice(0, 10))
    day.setUTCDate(day.getUTCDate() + 1)
  }
  return all
})
const tokenMax = computed(() => Math.max(0, ...rows.value.flatMap(person => person.daily.map(day => Number(day.tokens)))))
const imageMax = computed(() => Math.max(0, ...rows.value.flatMap(person => person.daily.map(day => Number(day.images)))))

function value(person, day, key) { return Number(person.daily.find(row => row.key === day)?.[key] || 0) }
function barHeight(amount, max) { return amount && max ? `${Math.max(2, Number(amount) / max * 100)}%` : '0%' }
function tone(amount, key) {
  const average = rows.value.reduce((sum, row) => sum + Number(row[key] || 0), 0) / rows.value.length
  return Number(amount) > average ? 'above-average' : Number(amount) < average ? 'below-average' : ''
}
function setPreset(value) {
  preset.value = value
  range.value = rangeFor(value)
  draft.value = { ...range.value }
  load()
}
function applyRange() {
  const from = new Date(`${draft.value.from}T00:00:00Z`)
  const to = new Date(`${draft.value.to}T00:00:00Z`)
  if (!draft.value.from || !draft.value.to || Number.isNaN(from.valueOf()) || Number.isNaN(to.valueOf()) || from > to || (to - from) / 86400000 > 366) {
    error.value = '请选择有效的起止日期，范围最长 367 天'
    return
  }
  preset.value = ''
  range.value = { ...draft.value }
  load()
}
function remove(id) { emit('update:selected', props.selected.filter(person => person.id !== id)) }
function add(person) {
  if (props.selected.length >= 8) return
  emit('update:selected', [...props.selected, { id: person.id, name: person.name }])
  candidateSearch.value = ''
  candidates.value = []
}
async function loadCandidates(term, current) {
  try {
    const result = await getJson(`/api/admin/relay/usage/compare/candidates?search=${encodeURIComponent(term)}`)
    if (current === candidateRequest) candidates.value = result
  } catch (cause) {
    if (current === candidateRequest) error.value = readableRelayError(cause)
  }
}
watch(candidateSearch, (value, _old, onCleanup) => {
  const current = ++candidateRequest
  const term = value.trim()
  if (!term) { candidates.value = []; return }
  // Debounce name lookup; only the newest response may replace the suggestions.
  const timer = setTimeout(() => loadCandidates(term, current), 250)
  onCleanup(() => clearTimeout(timer))
})
async function load() {
  const current = ++request
  if (props.selected.length < 2 || props.selected.length > 8) {
    rows.value = []
    error.value = '请选择 2 至 8 名员工'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ ...range.value })
    for (const person of props.selected) query.append('userIds', person.id)
    const result = await getJson(`/api/admin/relay/usage/compare?${query}`)
    if (current === request) rows.value = result.employees || []
  } catch (cause) {
    if (current === request) {
      rows.value = []
      error.value = readableRelayError(cause)
    }
  } finally {
    if (current === request) loading.value = false
  }
}
watch(() => props.selected.map(person => person.id).join(','), load)
onMounted(load)
</script>
