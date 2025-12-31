import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ErrorAlert from '../ErrorAlert.vue'
import type { AppError } from '@/composables/useApiError'

describe('ErrorAlert', () => {
  it('does not render when error is null', () => {
    const wrapper = mount(ErrorAlert, {
      props: { error: null }
    })
    expect(wrapper.findComponent({ name: 'VAlert' }).exists()).toBe(false)
  })

  it('renders error message from string', () => {
    const wrapper = mount(ErrorAlert, {
      props: { error: 'Something went wrong' }
    })
    expect(wrapper.text()).toContain('Something went wrong')
  })

  it('renders error message from Error object', () => {
    const wrapper = mount(ErrorAlert, {
      props: { error: new Error('Network failure') }
    })
    expect(wrapper.text()).toContain('Network failure')
  })

  it('renders error message from AppError', () => {
    const appError: AppError = {
      code: 'NETWORK_ERROR',
      message: 'Failed to connect',
      retryable: true
    }
    const wrapper = mount(ErrorAlert, {
      props: { error: appError }
    })
    expect(wrapper.text()).toContain('Failed to connect')
    expect(wrapper.text()).toContain('NETWORK_ERROR')
  })

  it('shows retry button when onRetry provided and error is retryable', () => {
    const appError: AppError = {
      code: 'NETWORK_ERROR',
      message: 'Failed to connect',
      retryable: true
    }
    const onRetry = vi.fn()
    const wrapper = mount(ErrorAlert, {
      props: { error: appError, onRetry }
    })
    const retryBtn = wrapper.findComponent({ name: 'VBtn' })
    expect(retryBtn.exists()).toBe(true)
    expect(retryBtn.text()).toContain('Retry')
  })

  it('hides retry button when error is not retryable', () => {
    const appError: AppError = {
      code: 'VALIDATION_ERROR',
      message: 'Invalid input',
      retryable: false
    }
    const onRetry = vi.fn()
    const wrapper = mount(ErrorAlert, {
      props: { error: appError, onRetry }
    })
    const retryBtn = wrapper.findComponent({ name: 'VBtn' })
    expect(retryBtn.exists()).toBe(false)
  })

  it('calls onRetry when retry button is clicked', async () => {
    const onRetry = vi.fn()
    const wrapper = mount(ErrorAlert, {
      props: {
        error: 'Network error',
        onRetry
      }
    })
    const retryBtn = wrapper.findComponent({ name: 'VBtn' })
    await retryBtn.trigger('click')
    expect(onRetry).toHaveBeenCalled()
  })

  it('shows close button when dismissible is true', () => {
    const wrapper = mount(ErrorAlert, {
      props: {
        error: 'Error message',
        dismissible: true
      }
    })
    const alert = wrapper.findComponent({ name: 'VAlert' })
    expect(alert.props('closable')).toBe(true)
  })
})
