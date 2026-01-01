import type { Meta, StoryObj } from '@storybook/vue3-vite'
import CollectionBarChart from './CollectionBarChart.vue'

const meta: Meta<typeof CollectionBarChart> = {
  title: 'Charts/CollectionBarChart',
  component: CollectionBarChart,
  tags: ['autodocs'],
  argTypes: {
    collections: {
      control: 'object',
      description: 'Array of collection statistics',
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

export const SingleCollection: Story = {
  args: {
    collections: [
      { name: 'main', documentCount: 50, chunkCount: 300 },
    ],
  },
}

export const ManyCollections: Story = {
  args: {
    collections: [
      { name: 'docs', documentCount: 100, chunkCount: 500 },
      { name: 'api', documentCount: 45, chunkCount: 280 },
      { name: 'tutorials', documentCount: 30, chunkCount: 180 },
      { name: 'reference', documentCount: 25, chunkCount: 150 },
      { name: 'guides', documentCount: 20, chunkCount: 120 },
      { name: 'faq', documentCount: 15, chunkCount: 75 },
    ],
  },
}

export const Empty: Story = {
  args: {
    collections: [],
  },
}
