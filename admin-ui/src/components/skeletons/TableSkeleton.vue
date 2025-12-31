<script setup lang="ts">
defineProps<{
  rows?: number
  columns?: number
}>()
</script>

<template>
  <v-card>
    <!-- Header row -->
    <div class="d-flex pa-4 bg-grey-lighten-4">
      <v-skeleton-loader
        v-for="i in (columns ?? 5)"
        :key="'header-' + i"
        type="text"
        :width="i === 1 ? '150px' : '100px'"
        class="mr-4"
      />
    </div>
    <v-divider />
    <!-- Data rows -->
    <div v-for="row in (rows ?? 5)" :key="'row-' + row" class="d-flex align-center pa-4">
      <v-skeleton-loader type="text" width="150px" class="mr-4" />
      <v-skeleton-loader type="text" width="120px" class="mr-4" />
      <v-skeleton-loader type="chip" width="80px" class="mr-4" />
      <v-skeleton-loader type="text" width="60px" class="mr-4" />
      <div class="d-flex ml-auto">
        <v-skeleton-loader type="button" width="36px" class="mr-2" />
        <v-skeleton-loader type="button" width="36px" />
      </div>
    </div>
  </v-card>
</template>
