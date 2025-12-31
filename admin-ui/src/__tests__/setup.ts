import { config } from '@vue/test-utils'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'

// Create Vuetify instance for tests
const vuetify = createVuetify({
  components,
  directives
})

// Global plugins for all tests
config.global.plugins = [vuetify]

// Stub router-link and router-view
config.global.stubs = {
  'router-link': true,
  'router-view': true
}
