import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

export interface KeyboardShortcut {
  key: string
  ctrl?: boolean
  meta?: boolean // For Mac Cmd key
  shift?: boolean
  alt?: boolean
  description: string
  action: () => void
}

// Global state for the search dialog
let searchDialogCallback: (() => void) | null = null

export function setSearchDialogCallback(callback: () => void) {
  searchDialogCallback = callback
}

export function useKeyboardShortcuts() {
  const router = useRouter()

  const shortcuts: KeyboardShortcut[] = [
    {
      key: 'k',
      ctrl: true,
      meta: true,
      description: 'Open search',
      action: () => {
        if (searchDialogCallback) {
          searchDialogCallback()
        }
      }
    },
    {
      key: 'u',
      ctrl: true,
      meta: true,
      description: 'Upload document',
      action: () => router.push('/documents/upload')
    },
    {
      key: 'd',
      ctrl: true,
      meta: true,
      description: 'Go to dashboard',
      action: () => router.push('/')
    },
    {
      key: 'g',
      ctrl: true,
      meta: true,
      description: 'Go to documents',
      action: () => router.push('/documents')
    }
  ]

  function handleKeyDown(event: KeyboardEvent) {
    // Don't trigger shortcuts when typing in inputs
    const target = event.target as HTMLElement
    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) {
      // Allow Escape to blur input
      if (event.key === 'Escape') {
        target.blur()
      }
      return
    }

    for (const shortcut of shortcuts) {
      const ctrlOrMeta = shortcut.ctrl || shortcut.meta
      const isCtrlOrMetaPressed = event.ctrlKey || event.metaKey

      if (
        event.key.toLowerCase() === shortcut.key.toLowerCase() &&
        (ctrlOrMeta ? isCtrlOrMetaPressed : true) &&
        (shortcut.shift ? event.shiftKey : !event.shiftKey) &&
        (shortcut.alt ? event.altKey : !event.altKey)
      ) {
        event.preventDefault()
        shortcut.action()
        return
      }
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', handleKeyDown)
  })

  onUnmounted(() => {
    window.removeEventListener('keydown', handleKeyDown)
  })

  return {
    shortcuts
  }
}
