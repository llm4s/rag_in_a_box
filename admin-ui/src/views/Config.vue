<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const configOptions = [
  {
    title: 'Runtime Configuration',
    subtitle: 'Modify runtime settings that take effect immediately or on restart',
    icon: 'mdi-tune',
    color: 'primary',
    to: '/config/runtime'
  },
  {
    title: 'Collection Configuration',
    subtitle: 'Configure chunking strategies per collection',
    icon: 'mdi-folder-cog',
    color: 'secondary',
    to: '/config/collections'
  }
]
</script>

<template>
  <div>
    <h1 class="text-h4 mb-6">Configuration</h1>

    <v-row>
      <v-col cols="12" md="6" v-for="option in configOptions" :key="option.to">
        <v-card
          class="pa-6"
          :color="option.color"
          variant="tonal"
          hover
          @click="router.push(option.to)"
        >
          <div class="d-flex align-center">
            <v-icon size="48" class="mr-4">{{ option.icon }}</v-icon>
            <div>
              <div class="text-h6">{{ option.title }}</div>
              <div class="text-body-2">{{ option.subtitle }}</div>
            </div>
          </div>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>
