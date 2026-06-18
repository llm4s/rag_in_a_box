import client from './client'
import type {
  Experiment,
  ExperimentListResponse,
  CreateExperimentRequest,
  CreateExperimentResponse,
  ExperimentOperationResponse,
  ExperimentResults,
  ExperimentStatus
} from '@/types/api'

/**
 * Create a new experiment.
 */
export async function createExperiment(request: CreateExperimentRequest): Promise<CreateExperimentResponse> {
  const response = await client.post<CreateExperimentResponse>('/experiments', request)
  return response.data
}

/**
 * List all experiments, optionally filtered by status.
 */
export async function listExperiments(status?: ExperimentStatus): Promise<ExperimentListResponse> {
  const params = status ? `?status=${status}` : ''
  const response = await client.get<ExperimentListResponse>(`/experiments${params}`)
  return response.data
}

/**
 * Get an experiment by ID.
 */
export async function getExperiment(id: string): Promise<Experiment> {
  const response = await client.get<Experiment>(`/experiments/${id}`)
  return response.data
}

/**
 * Get the currently running experiment.
 */
export async function getRunningExperiment(): Promise<Experiment | null> {
  try {
    const response = await client.get<Experiment>('/experiments/running')
    return response.data
  } catch (error: any) {
    if (error.response?.status === 404) {
      return null
    }
    throw error
  }
}

/**
 * Start an experiment (transitions from draft to running).
 */
export async function startExperiment(id: string): Promise<ExperimentOperationResponse> {
  const response = await client.put<ExperimentOperationResponse>(`/experiments/${id}/start`)
  return response.data
}

/**
 * Stop a running experiment.
 */
export async function stopExperiment(id: string): Promise<ExperimentOperationResponse> {
  const response = await client.put<ExperimentOperationResponse>(`/experiments/${id}/stop`)
  return response.data
}

/**
 * Archive a completed or draft experiment.
 */
export async function archiveExperiment(id: string): Promise<ExperimentOperationResponse> {
  const response = await client.put<ExperimentOperationResponse>(`/experiments/${id}/archive`)
  return response.data
}

/**
 * Delete a draft experiment.
 */
export async function deleteExperiment(id: string): Promise<ExperimentOperationResponse> {
  const response = await client.delete<ExperimentOperationResponse>(`/experiments/${id}`)
  return response.data
}

/**
 * Get experiment results with statistical analysis.
 */
export async function getExperimentResults(id: string): Promise<ExperimentResults> {
  const response = await client.get<ExperimentResults>(`/experiments/${id}/results`)
  return response.data
}
