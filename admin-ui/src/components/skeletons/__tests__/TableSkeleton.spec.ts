import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TableSkeleton from '../TableSkeleton.vue'

describe('TableSkeleton', () => {
  it('renders default 5 rows', () => {
    const wrapper = mount(TableSkeleton)
    // Find data rows (excluding header)
    const rows = wrapper.findAll('.d-flex.align-center.pa-4')
    expect(rows.length).toBe(5)
  })

  it('renders custom number of rows', () => {
    const wrapper = mount(TableSkeleton, {
      props: { rows: 3 }
    })
    const rows = wrapper.findAll('.d-flex.align-center.pa-4')
    expect(rows.length).toBe(3)
  })

  it('renders header row with skeleton loaders', () => {
    const wrapper = mount(TableSkeleton, {
      props: { columns: 4 }
    })
    const headerRow = wrapper.find('.bg-grey-lighten-4')
    expect(headerRow.exists()).toBe(true)
  })

  it('contains a card wrapper', () => {
    const wrapper = mount(TableSkeleton)
    expect(wrapper.findComponent({ name: 'VCard' }).exists()).toBe(true)
  })
})
