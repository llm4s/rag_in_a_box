import type { Meta, StoryObj } from '@storybook/vue3-vite'
import ErrorAlert from './ErrorAlert.vue'

const meta: Meta<typeof ErrorAlert> = {
  title: 'Components/ErrorAlert',
  component: ErrorAlert,
  tags: ['autodocs'],
  argTypes: {
    error: {
      control: 'object',
      description: 'Error object with code, message, and retryable flag',
    },
    dismissible: {
      control: 'boolean',
      description: 'Whether the alert can be dismissed',
    },
  },
}

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    error: {
      code: 'NETWORK_ERROR',
      message: 'Failed to connect to the server. Please check your connection.',
      retryable: true,
    },
  },
}

export const NonRetryable: Story = {
  args: {
    error: {
      code: 'VALIDATION_ERROR',
      message: 'The document format is invalid and cannot be processed.',
      retryable: false,
    },
  },
}

export const Dismissible: Story = {
  args: {
    error: {
      code: 'TIMEOUT_ERROR',
      message: 'The request timed out. Please try again.',
      retryable: true,
    },
    dismissible: true,
  },
}

export const LongMessage: Story = {
  args: {
    error: {
      code: 'SERVER_ERROR',
      message: 'An unexpected error occurred while processing your request. The server returned an internal error. This may be a temporary issue. Please try again in a few moments or contact support if the problem persists.',
      retryable: true,
    },
  },
}
