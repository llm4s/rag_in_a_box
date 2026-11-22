import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import QueryConfigSnapshotView from '../QueryConfigSnapshotView.vue'
import QueryLogDetail from '../QueryLogDetail.vue'
import type { QueryConfigSnapshot, QueryLogEntry } from '@/types/api'

const row = (wrapper: ReturnType<typeof mount>, key: string) => wrapper.find(`[data-test="snapshot-${key}"]`)

describe('QueryConfigSnapshotView', () => {
  it('shows every recorded setting', () => {
    const snapshot: QueryConfigSnapshot = {
      topK: 8,
      fusionStrategy: 'rrf',
      rrfK: 60,
      reranking: 'cohere',
      similarityThreshold: 0.25,
      llmTemperature: 0.1,
      systemPrompt: 'Answer briefly.'
    }
    const wrapper = mount(QueryConfigSnapshotView, { props: { snapshot } })

    expect(row(wrapper, 'topK').text()).toContain('Top K')
    expect(row(wrapper, 'topK').text()).toContain('8')
    expect(row(wrapper, 'fusionStrategy').text()).toContain('rrf')
    expect(row(wrapper, 'rrfK').text()).toContain('60')
    expect(row(wrapper, 'reranking').text()).toContain('cohere')
    expect(row(wrapper, 'similarityThreshold').text()).toContain('0.25')
    expect(row(wrapper, 'llmTemperature').text()).toContain('0.1')
    expect(row(wrapper, 'systemPrompt').text()).toContain('Answer briefly.')
    expect(wrapper.find('[data-test="snapshot-none"]').exists()).toBe(false)
  })

  it('leaves out settings that are absent or null (e.g. no similarity threshold, older entries)', () => {
    const wrapper = mount(QueryConfigSnapshotView, {
      props: { snapshot: { topK: 5, fusionStrategy: 'vector_only', similarityThreshold: null, systemPrompt: null } }
    })

    expect(row(wrapper, 'topK').exists()).toBe(true)
    expect(row(wrapper, 'fusionStrategy').text()).toContain('vector_only')
    for (const key of ['rrfK', 'reranking', 'similarityThreshold', 'llmTemperature', 'systemPrompt']) {
      expect(row(wrapper, key).exists(), key).toBe(false)
    }
  })

  it('shows a similarity threshold of 0', () => {
    const wrapper = mount(QueryConfigSnapshotView, {
      props: { snapshot: { topK: 5, fusionStrategy: 'rrf', similarityThreshold: 0 } }
    })
    expect(row(wrapper, 'similarityThreshold').text()).toContain('0')
  })

  it('truncates a long system prompt, keeping the whole of it in the title', () => {
    const prompt = 'You are a helpful assistant. '.repeat(20).trim()
    const wrapper = mount(QueryConfigSnapshotView, {
      props: { snapshot: { topK: 5, fusionStrategy: 'rrf', systemPrompt: prompt }, promptMaxLength: 40 }
    })

    const cell = row(wrapper, 'systemPrompt').find('td')
    expect(cell.text()).toBe(`${prompt.slice(0, 40).trimEnd()}…`)
    expect(cell.attributes('title')).toBe(prompt)
  })

  it('says so when no configuration was recorded', () => {
    const wrapper = mount(QueryConfigSnapshotView, { props: { snapshot: null } })
    expect(wrapper.find('[data-test="snapshot-none"]').text()).toContain('No configuration was recorded')
  })
})

describe('QueryLogDetail', () => {
  const entry: QueryLogEntry = {
    id: 'q-1',
    queryText: 'What is solar power?',
    collectionPattern: 'docs',
    totalLatencyMs: 930,
    embeddingLatencyMs: 12,
    searchLatencyMs: 8,
    llmLatencyMs: 910,
    chunksRetrieved: 5,
    chunksUsed: 4,
    createdAt: '2024-01-01T00:00:00Z',
    experimentId: 'exp-1',
    variant: 'variant',
    costUsd: 0.0042,
    configSnapshot: { topK: 3, fusionStrategy: 'keyword_only', similarityThreshold: 0.4, reranking: 'none' }
  }

  it('shows the query, its metrics and the configuration it ran with', () => {
    const wrapper = mount(QueryLogDetail, { props: { entry } })
    const text = wrapper.text()

    expect(wrapper.find('[data-test="query-text"]').text()).toBe('What is solar power?')
    expect(text).toContain('930ms')
    expect(text).toContain('4/5')
    expect(text).toContain('exp-1 (variant)')
    expect(text).toContain('$0.0042')
    expect(row(wrapper, 'topK').text()).toContain('3')
    expect(row(wrapper, 'fusionStrategy').text()).toContain('keyword_only')
    expect(row(wrapper, 'similarityThreshold').text()).toContain('0.4')
    expect(row(wrapper, 'reranking').text()).toContain('none')
    expect(wrapper.find('[data-test="query-error"]').exists()).toBe(false)
  })

  it('shows why a failed query failed, and handles an entry without a snapshot', () => {
    const wrapper = mount(QueryLogDetail, {
      props: { entry: { ...entry, failed: true, errorMessage: 'LLM timed out', configSnapshot: null } }
    })

    expect(wrapper.find('[data-test="query-error"]').text()).toContain('LLM timed out')
    expect(wrapper.find('[data-test="snapshot-none"]').exists()).toBe(true)
  })
})
