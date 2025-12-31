import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatsCardSkeleton from '../StatsCardSkeleton.vue'

describe('StatsCardSkeleton', () => {
  it('renders default 4 skeleton cards', () => {
    const wrapper = mount(StatsCardSkeleton)
    const cols = wrapper.findAllComponents({ name: 'VCol' })
    expect(cols.length).toBe(4)
  })

  it('renders custom number of skeleton cards', () => {
    const wrapper = mount(StatsCardSkeleton, {
      props: { count: 2 }
    })
    const cols = wrapper.findAllComponents({ name: 'VCol' })
    expect(cols.length).toBe(2)
  })

  it('contains skeleton loaders in each card', () => {
    const wrapper = mount(StatsCardSkeleton, {
      props: { count: 1 }
    })
    const skeletons = wrapper.findAllComponents({ name: 'VSkeletonLoader' })
    expect(skeletons.length).toBeGreaterThan(0)
  })
})
