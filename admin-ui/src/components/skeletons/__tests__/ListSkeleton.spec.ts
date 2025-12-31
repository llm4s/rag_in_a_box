import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ListSkeleton from '../ListSkeleton.vue'

describe('ListSkeleton', () => {
  it('renders default 5 list items', () => {
    const wrapper = mount(ListSkeleton)
    const items = wrapper.findAllComponents({ name: 'VListItem' })
    expect(items.length).toBe(5)
  })

  it('renders custom number of items', () => {
    const wrapper = mount(ListSkeleton, {
      props: { items: 3 }
    })
    const items = wrapper.findAllComponents({ name: 'VListItem' })
    expect(items.length).toBe(3)
  })

  it('shows avatar when showAvatar is true', () => {
    const wrapper = mount(ListSkeleton, {
      props: { showAvatar: true, items: 1 }
    })
    // Check for avatar skeleton loader
    const avatarSkeletons = wrapper.findAllComponents({ name: 'VSkeletonLoader' })
      .filter(s => s.props('type') === 'avatar')
    expect(avatarSkeletons.length).toBeGreaterThan(0)
  })

  it('contains a card and list wrapper', () => {
    const wrapper = mount(ListSkeleton)
    expect(wrapper.findComponent({ name: 'VCard' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'VList' }).exists()).toBe(true)
  })
})
