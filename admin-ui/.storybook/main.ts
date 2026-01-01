import type { StorybookConfig } from '@storybook/vue3-vite'

const config: StorybookConfig = {
  stories: [
    '../src/**/*.mdx',
    '../src/**/*.stories.@(js|jsx|mjs|ts|tsx)'
  ],
  addons: [
    '@storybook/addon-a11y',
    '@storybook/addon-docs',
  ],
  framework: '@storybook/vue3-vite',
  viteFinal: async (config) => {
    // Remove PWA plugin from Storybook builds to avoid service worker conflicts
    if (config.plugins) {
      config.plugins = (config.plugins as Array<{ name?: string }>).filter((plugin) => {
        const name = plugin?.name || ''
        return !name.includes('pwa') && !name.includes('PWA')
      })
    }
    return config
  },
}

export default config