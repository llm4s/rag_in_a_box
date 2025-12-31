<script setup lang="ts">
defineProps<{
  count?: number
}>()
</script>

<template>
  <v-row>
    <v-col v-for="i in (count ?? 4)" :key="i" cols="12" sm="6" md="3">
      <v-card class="pa-4">
        <div class="d-flex align-center">
          <v-skeleton-loader type="avatar" class="mr-4" />
          <div class="flex-grow-1">
            <v-skeleton-loader type="heading" class="mb-2" />
            <v-skeleton-loader type="text" width="60%" />
          </div>
        </div>
      </v-card>
    </v-col>
  </v-row>
</template>
