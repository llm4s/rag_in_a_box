<script setup lang="ts">
import { computed } from 'vue'
import { Doughnut } from 'vue-chartjs'
import {
  Chart as ChartJS,
  Title,
  Tooltip,
  Legend,
  ArcElement
} from 'chart.js'
import type { CollectionStats } from '@/types/api'

ChartJS.register(Title, Tooltip, Legend, ArcElement)

const props = defineProps<{
  collections: CollectionStats[]
}>()

const colors = ['#1976D2', '#42A5F5', '#90CAF9', '#BBDEFB', '#E3F2FD', '#0D47A1', '#1565C0', '#1E88E5']

const chartData = computed(() => ({
  labels: props.collections.map(c => c.name || 'default'),
  datasets: [
    {
      backgroundColor: colors.slice(0, props.collections.length),
      data: props.collections.map(c => c.chunkCount)
    }
  ]
}))

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      position: 'right' as const
    }
  }
}
</script>

<template>
  <div style="height: 250px">
    <Doughnut :data="chartData" :options="chartOptions" />
  </div>
</template>
