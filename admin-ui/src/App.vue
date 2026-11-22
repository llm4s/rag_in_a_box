<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useDisplay } from 'vuetify'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useStatsStore } from '@/stores/stats'
import { useWebSocketStore } from '@/stores/websocket'
import { useTheme } from '@/composables/useTheme'
import { useKeyboardShortcuts, setSearchDialogCallback } from '@/composables/useKeyboardShortcuts'
import NotificationContainer from '@/components/NotificationContainer.vue'
import SearchDialog from '@/components/SearchDialog.vue'
import KeyboardShortcutsDialog from '@/components/KeyboardShortcutsDialog.vue'

const statsStore = useStatsStore()
const wsStore = useWebSocketStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const { isDark, toggleTheme } = useTheme()
const { mobile } = useDisplay()
const { shortcuts } = useKeyboardShortcuts()

// Drawer starts closed on mobile, open on desktop
const drawer = ref(!mobile.value)
const healthStatus = ref<'healthy' | 'unhealthy' | 'unknown'>('unknown')
const searchDialog = ref(false)
const shortcutsDialog = ref(false)

// Register search dialog callback
setSearchDialogCallback(() => {
  searchDialog.value = true
})

// Listen for '?' key to show shortcuts help
function handleQuestionMark(event: KeyboardEvent) {
  const target = event.target as HTMLElement
  if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') return

  if (event.key === '?') {
    event.preventDefault()
    shortcutsDialog.value = true
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleQuestionMark)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleQuestionMark)
})

interface NavItem {
  title?: string
  icon?: string
  to?: string
  divider?: boolean
  /** Hidden for non-admins (the route itself has meta.requiresAdmin) */
  adminOnly?: boolean
}

const allNavItems: NavItem[] = [
  { title: 'Dashboard', icon: 'mdi-view-dashboard', to: '/' },
  { title: 'Chat', icon: 'mdi-chat', to: '/chat' },
  { divider: true },
  { title: 'Documents', icon: 'mdi-file-document-multiple', to: '/documents' },
  { title: 'Upload', icon: 'mdi-upload', to: '/documents/upload' },
  { divider: true },
  { title: 'Analytics', icon: 'mdi-chart-line', to: '/analytics', adminOnly: true },
  { title: 'Optimization', icon: 'mdi-lightbulb-on', to: '/suggestions' },
  { title: 'Experiments', icon: 'mdi-flask', to: '/experiments', adminOnly: true },
  { divider: true },
  { title: 'Configuration', icon: 'mdi-cog', to: '/config' },
  { title: 'Runtime Config', icon: 'mdi-tune', to: '/config/runtime', adminOnly: true },
  { title: 'Collections', icon: 'mdi-folder-cog', to: '/config/collections' },
  { divider: true },
  { title: 'Users', icon: 'mdi-account-multiple', to: '/users', adminOnly: true },
  { title: 'Groups', icon: 'mdi-account-group', to: '/groups', adminOnly: true },
  { title: 'Permissions', icon: 'mdi-shield-lock', to: '/permissions', adminOnly: true },
  { divider: true },
  { title: 'Chunking Preview', icon: 'mdi-scissors-cutting', to: '/chunking' },
  { title: 'Visibility', icon: 'mdi-eye', to: '/visibility', adminOnly: true },
  { title: 'Ingestion', icon: 'mdi-database-import', to: '/ingestion', adminOnly: true },
  { divider: true },
  { title: 'API Docs', icon: 'mdi-api', to: '/api-docs' },
]

// Admin-only entries are hidden for non-admins; collapse leftover dividers
const navItems = computed(() => {
  const visible = allNavItems.filter((item) => !item.adminOnly || auth.isAdmin)
  return visible.filter((item, i) =>
    !item.divider || (i > 0 && i < visible.length - 1 && !visible[i - 1].divider)
  )
})

const isLoginPage = computed(() => route.name === 'login')
/** Auth established (or not required): safe to call the API and open the websocket. */
const authReady = computed(() => auth.initialized && auth.isAuthenticated)
const showUserMenu = computed(() => auth.authRequired && auth.user !== null)

async function handleLogout() {
  wsStore.disconnect()
  await auth.logout()
  await router.push({ name: 'login' })
}

async function checkHealth() {
  try {
    const response = await fetch('/health/ready')
    healthStatus.value = response.ok ? 'healthy' : 'unhealthy'
  } catch {
    healthStatus.value = 'unhealthy'
  }
}

// Don't hit authenticated endpoints (or open /ws) before auth is established
watch(authReady, (ready) => {
  if (ready) {
    statsStore.fetchStats()
    wsStore.connect()
  } else {
    wsStore.disconnect()
  }
}, { immediate: true })

onMounted(() => {
  checkHealth()
  // Refresh health every 30 seconds
  setInterval(checkHealth, 30000)
})

onUnmounted(() => {
  wsStore.disconnect()
})
</script>

<template>
  <v-app>
    <!-- Skip to main content link for keyboard/screen reader users -->
    <a href="#main-content" class="skip-link">Skip to main content</a>

    <!-- App Bar -->
    <v-app-bar color="primary" :prominent="!mobile">
      <v-app-bar-nav-icon v-if="!isLoginPage" @click="drawer = !drawer"></v-app-bar-nav-icon>

      <v-toolbar-title>
        <span class="font-weight-bold">RAG in a Box</span>
        <span class="text-caption ml-2">Admin</span>
      </v-toolbar-title>

      <v-spacer></v-spacer>

      <!-- Search Button -->
      <v-btn
        v-if="!isLoginPage"
        icon
        @click="searchDialog = true"
        aria-label="Search (Ctrl+K)"
        class="mr-1"
      >
        <v-icon>mdi-magnify</v-icon>
      </v-btn>

      <!-- Health Status -->
      <v-chip
        :color="healthStatus === 'healthy' ? 'success' : healthStatus === 'unhealthy' ? 'error' : 'grey'"
        variant="flat"
        size="small"
        class="mr-2 mr-sm-4"
      >
        <v-icon :start="!mobile" size="small">
          {{ healthStatus === 'healthy' ? 'mdi-check-circle' : healthStatus === 'unhealthy' ? 'mdi-alert-circle' : 'mdi-help-circle' }}
        </v-icon>
        <span class="d-none d-sm-inline">
          {{ healthStatus === 'healthy' ? 'Healthy' : healthStatus === 'unhealthy' ? 'Unhealthy' : 'Checking...' }}
        </span>
      </v-chip>

      <!-- Dark Mode Toggle -->
      <v-btn icon @click="toggleTheme" :aria-label="isDark ? 'Switch to light mode' : 'Switch to dark mode'">
        <v-icon>{{ isDark ? 'mdi-weather-sunny' : 'mdi-weather-night' }}</v-icon>
      </v-btn>

      <!-- Current user / logout (only when the server requires authentication) -->
      <v-menu v-if="showUserMenu" location="bottom end">
        <template v-slot:activator="{ props }">
          <v-btn v-bind="props" :icon="mobile" :aria-label="`Signed in as ${auth.username}`" class="ml-1">
            <v-icon v-if="mobile">mdi-account-circle</v-icon>
            <template v-else>
              <v-icon start>mdi-account-circle</v-icon>
              <span class="text-none">{{ auth.username }}</span>
            </template>
          </v-btn>
        </template>
        <v-list density="compact" min-width="200">
          <v-list-item :title="auth.username ?? ''" :subtitle="auth.isAdmin ? 'Administrator' : 'User'" prepend-icon="mdi-account"></v-list-item>
          <v-divider></v-divider>
          <v-list-item prepend-icon="mdi-logout" title="Sign out" @click="handleLogout"></v-list-item>
        </v-list>
      </v-menu>
    </v-app-bar>

    <!-- Navigation Drawer -->
    <v-navigation-drawer v-if="!isLoginPage" v-model="drawer" :permanent="!mobile">
      <v-list density="compact" nav>
        <template v-for="(item, index) in navItems" :key="index">
          <v-divider v-if="item.divider" class="my-2"></v-divider>
          <v-list-item
            v-else
            :prepend-icon="item.icon"
            :title="item.title"
            :to="item.to"
            @click="mobile && (drawer = false)"
          ></v-list-item>
        </template>
      </v-list>

      <template v-slot:append>
        <div class="pa-4">
          <v-divider class="mb-4"></v-divider>
          <div class="text-caption text-grey">
            <div>Documents: {{ statsStore.stats?.documentCount ?? '...' }}</div>
            <div>Chunks: {{ statsStore.stats?.chunkCount ?? '...' }}</div>
          </div>
        </div>
      </template>
    </v-navigation-drawer>

    <!-- Main Content -->
    <v-main>
      <v-container id="main-content" fluid class="pa-2 pa-sm-4 pa-md-6" tabindex="-1">
        <router-view />
      </v-container>
    </v-main>

    <!-- Global Notifications -->
    <NotificationContainer />

    <!-- Search Dialog -->
    <SearchDialog v-model="searchDialog" />

    <!-- Keyboard Shortcuts Help -->
    <KeyboardShortcutsDialog v-model="shortcutsDialog" :shortcuts="shortcuts" />
  </v-app>
</template>

