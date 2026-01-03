<script setup lang="ts">
import { computed, ref, onMounted, watch } from 'vue'
import { Chart, registerables } from 'chart.js'

Chart.register(...registerables)

const props = defineProps<{
  p50: number
  p95: number
  p99: number
  average: number
}>()

// eslint-disable-next-line no-undef
const chartRef = ref<HTMLCanvasElement | null>(null)
let chartInstance: Chart | null = null

const chartData = computed(() => ({
  labels: ['Average', 'P50', 'P95', 'P99'],
  datasets: [
    {
      label: 'Latency (ms)',
      data: [props.average, props.p50, props.p95, props.p99],
      backgroundColor: [
        'rgba(33, 150, 243, 0.7)',
        'rgba(76, 175, 80, 0.7)',
        'rgba(255, 193, 7, 0.7)',
        'rgba(244, 67, 54, 0.7)'
      ],
      borderColor: [
        'rgb(33, 150, 243)',
        'rgb(76, 175, 80)',
        'rgb(255, 193, 7)',
        'rgb(244, 67, 54)'
      ],
      borderWidth: 1
    }
  ]
}))

function createChart() {
  if (!chartRef.value) return

  if (chartInstance) {
    chartInstance.destroy()
  }

  chartInstance = new Chart(chartRef.value, {
    type: 'bar',
    data: chartData.value,
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: false
        },
        tooltip: {
          callbacks: {
            label: (context) => `${(context.parsed.y ?? 0).toFixed(0)} ms`
          }
        }
      },
      scales: {
        y: {
          beginAtZero: true,
          title: {
            display: true,
            text: 'Milliseconds'
          }
        }
      }
    }
  })
}

onMounted(() => {
  createChart()
})

watch(() => [props.p50, props.p95, props.p99, props.average], () => {
  createChart()
})
</script>

<template>
  <div class="chart-container" style="height: 200px;">
    <canvas ref="chartRef"></canvas>
  </div>
</template>

<style scoped>
.chart-container {
  position: relative;
  width: 100%;
}
</style>
