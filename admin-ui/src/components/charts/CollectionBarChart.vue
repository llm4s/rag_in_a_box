<script setup lang="ts">
import { computed } from 'vue'
import { Bar } from 'vue-chartjs'
import {
  Chart as ChartJS,
  Title,
  Tooltip,
  Legend,
  BarElement,
  CategoryScale,
  LinearScale
} from 'chart.js'
import type { CollectionStats } from '@/types/api'

ChartJS.register(Title, Tooltip, Legend, BarElement, CategoryScale, LinearScale)

const props = defineProps<{
  collections: CollectionStats[]
}>()

const chartData = computed(() => ({
  labels: props.collections.map(c => c.name || 'default'),
  datasets: [
    {
      label: 'Documents',
      backgroundColor: '#1976D2',
      data: props.collections.map(c => c.documentCount)
    },
    {
      label: 'Chunks',
      backgroundColor: '#82B1FF',
      data: props.collections.map(c => c.chunkCount)
    }
  ]
}))

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      position: 'top' as const
    }
  },
  scales: {
    y: {
      beginAtZero: true
    }
  }
}
</script>

<template>
  <div style="height: 300px">
    <Bar :data="chartData" :options="chartOptions" />
  </div>
</template>
