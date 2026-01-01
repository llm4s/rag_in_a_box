import type { Meta, StoryObj } from '@storybook/vue3-vite'
import SearchDialog from './SearchDialog.vue'

const meta: Meta<typeof SearchDialog> = {
  title: 'Components/SearchDialog',
  component: SearchDialog,
  tags: ['autodocs'],
  argTypes: {
    modelValue: {
      control: 'boolean',
      description: 'Controls dialog visibility',
    },
  },
}

export default meta
type Story = StoryObj<typeof meta>

export const Open: Story = {
  args: {
    modelValue: true,
  },
}

export const Closed: Story = {
  args: {
    modelValue: false,
  },
}
