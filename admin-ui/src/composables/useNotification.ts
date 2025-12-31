import { ref, readonly } from 'vue'

export type NotificationType = 'success' | 'error' | 'warning' | 'info'

export interface Notification {
  id: number
  type: NotificationType
  message: string
  timeout: number
}

const notifications = ref<Notification[]>([])
let nextId = 1

function show(type: NotificationType, message: string, timeout = 5000) {
  const id = nextId++
  notifications.value.push({ id, type, message, timeout })

  if (timeout > 0) {
    setTimeout(() => {
      dismiss(id)
    }, timeout)
  }

  return id
}

function dismiss(id: number) {
  const index = notifications.value.findIndex(n => n.id === id)
  if (index !== -1) {
    notifications.value.splice(index, 1)
  }
}

function dismissAll() {
  notifications.value = []
}

export function useNotification() {
  return {
    notifications: readonly(notifications),

    success(message: string, timeout = 4000) {
      return show('success', message, timeout)
    },

    error(message: string, timeout = 0) {
      // Errors don't auto-dismiss by default
      return show('error', message, timeout)
    },

    warning(message: string, timeout = 6000) {
      return show('warning', message, timeout)
    },

    info(message: string, timeout = 5000) {
      return show('info', message, timeout)
    },

    dismiss,
    dismissAll
  }
}
