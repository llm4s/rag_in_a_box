import type { Meta, StoryObj } from '@storybook/vue3-vite'
import KeyboardShortcutsDialog from './KeyboardShortcutsDialog.vue'

const meta: Meta<typeof KeyboardShortcutsDialog> = {
  title: 'Components/KeyboardShortcutsDialog',
  component: KeyboardShortcutsDialog,
  tags: ['autodocs'],
  argTypes: {
    modelValue: {
      control: 'boolean',
      description: 'Controls dialog visibility',
    },
    shortcuts: {
      control: 'object',
      description: 'Array of keyboard shortcuts to display',
    },
  },
}

export default meta
type Story = StoryObj<typeof meta>

export const Open: Story = {
  args: {
    modelValue: true,
    shortcuts: [
      { key: 'k', ctrl: true, meta: true, description: 'Open search', action: () => {} },
      { key: 'u', ctrl: true, meta: true, description: 'Upload document', action: () => {} },
      { key: 'd', ctrl: true, meta: true, description: 'Go to documents', action: () => {} },
      { key: 'g', ctrl: true, meta: true, description: 'Go to dashboard', action: () => {} },
    ],
  },
}

export const FewShortcuts: Story = {
  args: {
    modelValue: true,
    shortcuts: [
      { key: 'k', ctrl: true, meta: true, description: 'Open search', action: () => {} },
      { key: 's', ctrl: true, meta: true, description: 'Save', action: () => {} },
    ],
  },
}
