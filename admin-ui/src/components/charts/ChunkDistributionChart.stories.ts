import type { Meta, StoryObj } from '@storybook/vue3-vite'
import ChunkDistributionChart from './ChunkDistributionChart.vue'

const meta: Meta<typeof ChunkDistributionChart> = {
  title: 'Charts/ChunkDistributionChart',
  component: ChunkDistributionChart,
  tags: ['autodocs'],
  argTypes: {
    collections: {
      control: 'object',
      description: 'Array of collection statistics for chunk distribution',
    },
  },
}

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    collections: [
      { name: 'default', documentCount: 25, chunkCount: 150 },
      { name: 'technical-docs', documentCount: 12, chunkCount: 89 },
      { name: 'guides', documentCount: 8, chunkCount: 45 },
    ],
  },
}

export const EvenDistribution: Story = {
  args: {
    collections: [
      { name: 'collection-a', documentCount: 10, chunkCount: 100 },
      { name: 'collection-b', documentCount: 10, chunkCount: 100 },
      { name: 'collection-c', documentCount: 10, chunkCount: 100 },
    ],
  },
}

export const DominantCollection: Story = {
  args: {
    collections: [
      { name: 'main', documentCount: 100, chunkCount: 800 },
      { name: 'secondary', documentCount: 10, chunkCount: 50 },
      { name: 'archive', documentCount: 5, chunkCount: 25 },
    ],
  },
}
