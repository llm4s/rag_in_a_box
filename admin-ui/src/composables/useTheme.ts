import { ref, watch } from 'vue'
import { useTheme as useVuetifyTheme } from 'vuetify'

const THEME_KEY = 'ragbox-theme'

// Get initial theme from localStorage or system preference
function getInitialTheme(): 'light' | 'dark' {
  const stored = localStorage.getItem(THEME_KEY)
  if (stored === 'light' || stored === 'dark') {
    return stored
  }
  // Check system preference
  if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark'
  }
  return 'light'
}

export function useTheme() {
  const vuetifyTheme = useVuetifyTheme()
  const isDark = ref(getInitialTheme() === 'dark')

  // Initialize theme
  vuetifyTheme.global.name.value = isDark.value ? 'dark' : 'light'

  // Watch for changes and persist
  watch(isDark, (newValue) => {
    const themeName = newValue ? 'dark' : 'light'
    vuetifyTheme.global.name.value = themeName
    localStorage.setItem(THEME_KEY, themeName)
  })

  function toggleTheme() {
    isDark.value = !isDark.value
  }

  function setTheme(dark: boolean) {
    isDark.value = dark
  }

  return {
    isDark,
    toggleTheme,
    setTheme
  }
}
