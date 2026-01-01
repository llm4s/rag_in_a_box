import type { Meta, StoryObj } from '@storybook/vue3-vite'
import TableSkeleton from './TableSkeleton.vue'

const meta: Meta<typeof TableSkeleton> = {
  title: 'Skeletons/TableSkeleton',
  component: TableSkeleton,
  tags: ['autodocs'],
  argTypes: {
    rows: {
      control: { type: 'number', min: 1, max: 20 },
      description: 'Number of skeleton rows to display',
    },
    columns: {
      control: { type: 'number', min: 1, max: 10 },
      description: 'Number of columns per row',
    },
  },
}

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    rows: 5,
    columns: 4,
  },
}

export const SmallTable: Story = {
  args: {
    rows: 3,
    columns: 2,
  },
}

export const LargeTable: Story = {
  args: {
    rows: 10,
    columns: 6,
  },
}
