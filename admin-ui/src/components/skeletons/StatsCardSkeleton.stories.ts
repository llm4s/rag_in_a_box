import type { Meta, StoryObj } from '@storybook/vue3-vite'
import StatsCardSkeleton from './StatsCardSkeleton.vue'

const meta: Meta<typeof StatsCardSkeleton> = {
  title: 'Skeletons/StatsCardSkeleton',
  component: StatsCardSkeleton,
  tags: ['autodocs'],
  argTypes: {
    count: {
      control: { type: 'number', min: 1, max: 8 },
      description: 'Number of skeleton cards to display',
    },
  },
}

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    count: 4,
  },
}

export const Single: Story = {
  args: {
    count: 1,
  },
}

export const Many: Story = {
  args: {
    count: 8,
  },
}
