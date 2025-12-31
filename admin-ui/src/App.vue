<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useStatsStore } from '@/stores/stats'
import { useTheme } from '@/composables/useTheme'
import NotificationContainer from '@/components/NotificationContainer.vue'

const statsStore = useStatsStore()
const { isDark, toggleTheme } = useTheme()

const drawer = ref(true)
const healthStatus = ref<'healthy' | 'unhealthy' | 'unknown'>('unknown')

const navItems = [
  { title: 'Dashboard', icon: 'mdi-view-dashboard', to: '/' },
  { title: 'Documents', icon: 'mdi-file-document-multiple', to: '/documents' },
  { title: 'Upload', icon: 'mdi-upload', to: '/documents/upload' },
  { divider: true },
  { title: 'Configuration', icon: 'mdi-cog', to: '/config' },
  { title: 'Runtime Config', icon: 'mdi-tune', to: '/config/runtime' },
  { title: 'Collections', icon: 'mdi-folder-cog', to: '/config/collections' },
  { divider: true },
  { title: 'Chunking Preview', icon: 'mdi-scissors-cutting', to: '/chunking' },
  { title: 'Visibility', icon: 'mdi-eye', to: '/visibility' },
  { title: 'Ingestion', icon: 'mdi-database-import', to: '/ingestion' },
]

async function checkHealth() {
  try {
    const response = await fetch('/health/ready')
    healthStatus.value = response.ok ? 'healthy' : 'unhealthy'
  } catch {
    healthStatus.value = 'unhealthy'
  }
}

onMounted(() => {
  checkHealth()
  statsStore.fetchStats()
  // Refresh health every 30 seconds
  setInterval(checkHealth, 30000)
})
</script>

<template>
  <v-app>
    <!-- App Bar -->
    <v-app-bar color="primary" prominent>
      <v-app-bar-nav-icon @click="drawer = !drawer"></v-app-bar-nav-icon>

      <v-toolbar-title>
        <span class="font-weight-bold">RAG in a Box</span>
        <span class="text-caption ml-2">Admin</span>
      </v-toolbar-title>

      <v-spacer></v-spacer>

      <!-- Health Status -->
      <v-chip
        :color="healthStatus === 'healthy' ? 'success' : healthStatus === 'unhealthy' ? 'error' : 'grey'"
        variant="flat"
        size="small"
        class="mr-4"
      >
        <v-icon start size="small">
          {{ healthStatus === 'healthy' ? 'mdi-check-circle' : healthStatus === 'unhealthy' ? 'mdi-alert-circle' : 'mdi-help-circle' }}
        </v-icon>
        {{ healthStatus === 'healthy' ? 'Healthy' : healthStatus === 'unhealthy' ? 'Unhealthy' : 'Checking...' }}
      </v-chip>

      <!-- Dark Mode Toggle -->
      <v-btn icon @click="toggleTheme" :aria-label="isDark ? 'Switch to light mode' : 'Switch to dark mode'">
        <v-icon>{{ isDark ? 'mdi-weather-sunny' : 'mdi-weather-night' }}</v-icon>
      </v-btn>
    </v-app-bar>

    <!-- Navigation Drawer -->
    <v-navigation-drawer v-model="drawer" permanent>
      <v-list density="compact" nav>
        <template v-for="(item, index) in navItems" :key="index">
          <v-divider v-if="item.divider" class="my-2"></v-divider>
          <v-list-item
            v-else
            :prepend-icon="item.icon"
            :title="item.title"
            :to="item.to"
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
      <v-container fluid class="pa-6">
        <router-view />
      </v-container>
    </v-main>

    <!-- Global Notifications -->
    <NotificationContainer />
  </v-app>
</template>

