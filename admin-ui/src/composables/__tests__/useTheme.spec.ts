import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] || null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    clear: vi.fn(() => {
      store = {}
    })
  }
})()

// Mock window.matchMedia
const matchMediaMock = vi.fn((query: string) => ({
  matches: query === '(prefers-color-scheme: dark)',
  media: query,
  onchange: null,
  addListener: vi.fn(),
  removeListener: vi.fn(),
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
  dispatchEvent: vi.fn()
}))

Object.defineProperty(window, 'localStorage', { value: localStorageMock })
Object.defineProperty(window, 'matchMedia', { value: matchMediaMock })

// Mock Vuetify's useTheme
vi.mock('vuetify', () => ({
  useTheme: () => ({
    global: {
      name: { value: 'light' }
    }
  })
}))

describe('useTheme', () => {
  beforeEach(() => {
    localStorageMock.clear()
    vi.clearAllMocks()
  })

  it('should use stored theme from localStorage', async () => {
    localStorageMock.getItem.mockReturnValue('dark')

    const { useTheme } = await import('../useTheme')
    const { isDark } = useTheme()

    expect(isDark.value).toBe(true)
  })

  it('should use light theme by default when no stored preference', async () => {
    localStorageMock.getItem.mockReturnValue(null)
    matchMediaMock.mockReturnValue({ matches: false })

    // Re-import to get fresh state
    vi.resetModules()
    const { useTheme } = await import('../useTheme')
    const { isDark } = useTheme()

    expect(isDark.value).toBe(false)
  })

  it('should toggle theme and persist to localStorage', async () => {
    localStorageMock.getItem.mockReturnValue('light')

    vi.resetModules()
    const { useTheme } = await import('../useTheme')
    const { isDark, toggleTheme } = useTheme()

    expect(isDark.value).toBe(false)

    toggleTheme()

    expect(isDark.value).toBe(true)
  })

  it('should set theme directly', async () => {
    localStorageMock.getItem.mockReturnValue('light')

    vi.resetModules()
    const { useTheme } = await import('../useTheme')
    const { isDark, setTheme } = useTheme()

    expect(isDark.value).toBe(false)

    setTheme(true)

    expect(isDark.value).toBe(true)
  })
})
