<script setup lang="ts">
defineProps<{
  items?: number
  showAvatar?: boolean
}>()
</script>

<template>
  <v-card>
    <v-list>
      <v-list-item v-for="i in (items ?? 5)" :key="i">
        <template v-slot:prepend v-if="showAvatar">
          <v-skeleton-loader type="avatar" />
        </template>
        <div>
          <v-skeleton-loader type="text" width="200px" class="mb-1" />
          <v-skeleton-loader type="text" width="300px" />
        </div>
        <template v-slot:append>
          <v-skeleton-loader type="chip" width="70px" />
        </template>
      </v-list-item>
    </v-list>
  </v-card>
</template>
