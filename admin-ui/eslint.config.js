// For more info, see https://github.com/storybookjs/eslint-plugin-storybook#configuration-flat-config-format
import storybook from "eslint-plugin-storybook";

import eslint from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'

export default tseslint.config(
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser
      },
      globals: {
        // Browser globals
        fetch: 'readonly',
        setInterval: 'readonly',
        setTimeout: 'readonly',
        clearInterval: 'readonly',
        clearTimeout: 'readonly',
        console: 'readonly',
        localStorage: 'readonly',
        confirm: 'readonly',
        alert: 'readonly',
        window: 'readonly',
        document: 'readonly',
        navigator: 'readonly',
        File: 'readonly',
        FileReader: 'readonly',
        FormData: 'readonly',
        URL: 'readonly',
        HTMLInputElement: 'readonly',
        HTMLElement: 'readonly',
        DragEvent: 'readonly',
        KeyboardEvent: 'readonly',
        Event: 'readonly',
        Promise: 'readonly'
      }
    }
  },
  {
    files: ['**/*.ts'],
    languageOptions: {
      globals: {
        console: 'readonly'
      }
    }
  },
  {
    rules: {
      // Vue specific rules - relaxed for Vuetify compatibility
      'vue/multi-word-component-names': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/first-attribute-linebreak': 'off',
      'vue/html-indent': 'off',
      'vue/v-slot-style': 'off',  // Allow both v-slot: and # syntax
      'vue/attributes-order': 'off',  // Don't enforce attribute order
      'vue/valid-v-slot': 'off',  // Vuetify uses dynamic slots

      // TypeScript rules
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],

      // General rules
      'no-console': ['warn', { allow: ['warn', 'error'] }]
    }
  },
  {
    ignores: ['dist/**', 'node_modules/**', '*.config.js', '*.config.ts', '**/*.d.ts']
  },
  storybook.configs["flat/recommended"]
);
