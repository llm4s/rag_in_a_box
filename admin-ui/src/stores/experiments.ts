import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  Experiment,
  ExperimentResults,
  CreateExperimentRequest,
  ExperimentStatus
} from '@/types/api'
import * as experimentsApi from '@/api/experiments'

export const useExperimentsStore = defineStore('experiments', () => {
  const experiments = ref<Experiment[]>([])
  const currentExperiment = ref<Experiment | null>(null)
  const runningExperiment = ref<Experiment | null>(null)
  const currentResults = ref<ExperimentResults | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // Filter state
  const statusFilter = ref<ExperimentStatus | null>(null)

  // Filtered experiments
  const filteredExperiments = computed((): Experiment[] => {
    if (!statusFilter.value) return experiments.value
    return experiments.value.filter((e) => e.status === statusFilter.value)
  })

  // Group experiments by status
  const draftExperiments = computed(() =>
    experiments.value.filter((e) => e.status === 'draft')
  )
  const runningExperiments = computed(() =>
    experiments.value.filter((e) => e.status === 'running')
  )
  const completedExperiments = computed(() =>
    experiments.value.filter((e) => e.status === 'completed')
  )
  const archivedExperiments = computed(() =>
    experiments.value.filter((e) => e.status === 'archived')
  )

  // Counts
  const totalCount = computed(() => experiments.value.length)
  const draftCount = computed(() => draftExperiments.value.length)
  const runningCount = computed(() => runningExperiments.value.length)
  const completedCount = computed(() => completedExperiments.value.length)

  async function fetchExperiments() {
    loading.value = true
    error.value = null
    try {
      const response = await experimentsApi.listExperiments()
      experiments.value = response.experiments
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch experiments'
    } finally {
      loading.value = false
    }
  }

  async function fetchRunningExperiment() {
    loading.value = true
    error.value = null
    try {
      runningExperiment.value = await experimentsApi.getRunningExperiment()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch running experiment'
    } finally {
      loading.value = false
    }
  }

  async function fetchExperiment(id: string) {
    loading.value = true
    error.value = null
    try {
      currentExperiment.value = await experimentsApi.getExperiment(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch experiment'
    } finally {
      loading.value = false
    }
  }

  async function fetchResults(id: string) {
    loading.value = true
    error.value = null
    try {
      currentResults.value = await experimentsApi.getExperimentResults(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch experiment results'
    } finally {
      loading.value = false
    }
  }

  async function createExperiment(request: CreateExperimentRequest): Promise<Experiment | null> {
    loading.value = true
    error.value = null
    try {
      const response = await experimentsApi.createExperiment(request)
      experiments.value.unshift(response.experiment)
      return response.experiment
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create experiment'
      return null
    } finally {
      loading.value = false
    }
  }

  async function startExperiment(id: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const response = await experimentsApi.startExperiment(id)
      if (response.success && response.experiment) {
        // Update the experiment in the list
        const index = experiments.value.findIndex((e) => e.id === id)
        if (index !== -1) {
          experiments.value[index] = response.experiment
        }
        runningExperiment.value = response.experiment
      } else {
        error.value = response.message
      }
      return response.success
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to start experiment'
      return false
    } finally {
      loading.value = false
    }
  }

  async function stopExperiment(id: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const response = await experimentsApi.stopExperiment(id)
      if (response.success && response.experiment) {
        // Update the experiment in the list
        const index = experiments.value.findIndex((e) => e.id === id)
        if (index !== -1) {
          experiments.value[index] = response.experiment
        }
        if (runningExperiment.value?.id === id) {
          runningExperiment.value = null
        }
      } else {
        error.value = response.message
      }
      return response.success
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to stop experiment'
      return false
    } finally {
      loading.value = false
    }
  }

  async function archiveExperiment(id: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const response = await experimentsApi.archiveExperiment(id)
      if (response.success && response.experiment) {
        // Update the experiment in the list
        const index = experiments.value.findIndex((e) => e.id === id)
        if (index !== -1) {
          experiments.value[index] = response.experiment
        }
      } else {
        error.value = response.message
      }
      return response.success
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to archive experiment'
      return false
    } finally {
      loading.value = false
    }
  }

  async function deleteExperiment(id: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const response = await experimentsApi.deleteExperiment(id)
      if (response.success) {
        // Remove the experiment from the list
        experiments.value = experiments.value.filter((e) => e.id !== id)
      } else {
        error.value = response.message
      }
      return response.success
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete experiment'
      return false
    } finally {
      loading.value = false
    }
  }

  function setStatusFilter(status: ExperimentStatus | null) {
    statusFilter.value = status
  }

  function clearError() {
    error.value = null
  }

  return {
    // State
    experiments,
    currentExperiment,
    runningExperiment,
    currentResults,
    loading,
    error,
    statusFilter,

    // Computed
    filteredExperiments,
    draftExperiments,
    runningExperiments,
    completedExperiments,
    archivedExperiments,
    totalCount,
    draftCount,
    runningCount,
    completedCount,

    // Actions
    fetchExperiments,
    fetchRunningExperiment,
    fetchExperiment,
    fetchResults,
    createExperiment,
    startExperiment,
    stopExperiment,
    archiveExperiment,
    deleteExperiment,
    setStatusFilter,
    clearError
  }
})
