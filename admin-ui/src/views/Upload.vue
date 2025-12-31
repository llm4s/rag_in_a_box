<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDocumentsStore } from '@/stores/documents'
import * as ingestionApi from '@/api/ingestion'

const router = useRouter()
const documentsStore = useDocumentsStore()

const tab = ref('text')
const content = ref('')
const collection = ref('')
const metadata = ref<{ key: string; value: string }[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const success = ref(false)

// File upload state
const files = ref<File[]>([])
const isDragging = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

// URL ingestion state
const url = ref('')

function addMetadata() {
  metadata.value.push({ key: '', value: '' })
}

function removeMetadata(index: number) {
  metadata.value.splice(index, 1)
}

// File upload handlers
function onDragOver(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}

function onDragLeave() {
  isDragging.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  if (e.dataTransfer?.files) {
    addFiles(Array.from(e.dataTransfer.files))
  }
}

function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files) {
    addFiles(Array.from(input.files))
  }
}

function addFiles(newFiles: File[]) {
  // Filter for text-based files
  const textFiles = newFiles.filter(f =>
    f.type.startsWith('text/') ||
    f.name.endsWith('.md') ||
    f.name.endsWith('.txt') ||
    f.name.endsWith('.json') ||
    f.name.endsWith('.yaml') ||
    f.name.endsWith('.yml') ||
    f.name.endsWith('.xml') ||
    f.name.endsWith('.csv') ||
    f.name.endsWith('.html') ||
    f.name.endsWith('.htm') ||
    f.type === ''  // Unknown type, try anyway
  )
  files.value = [...files.value, ...textFiles]
}

function removeFile(index: number) {
  files.value.splice(index, 1)
}

function triggerFileInput() {
  fileInputRef.value?.click()
}

async function readFileContent(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = () => reject(new Error(`Failed to read file: ${file.name}`))
    reader.readAsText(file)
  })
}

// Submit handlers for each tab
async function submitText() {
  if (!content.value.trim()) {
    error.value = 'Content is required'
    return
  }

  loading.value = true
  error.value = null

  try {
    const metadataObj = metadata.value.reduce((acc, { key, value }) => {
      if (key.trim()) {
        acc[key.trim()] = value
      }
      return acc
    }, {} as Record<string, string>)

    await documentsStore.createDocument({
      content: content.value,
      collection: collection.value || undefined,
      metadata: Object.keys(metadataObj).length > 0 ? metadataObj : undefined
    })

    success.value = true
    setTimeout(() => {
      router.push('/documents')
    }, 1500)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to create document'
  } finally {
    loading.value = false
  }
}

async function submitFiles() {
  if (files.value.length === 0) {
    error.value = 'Please select at least one file'
    return
  }

  loading.value = true
  error.value = null

  try {
    const metadataObj = metadata.value.reduce((acc, { key, value }) => {
      if (key.trim()) {
        acc[key.trim()] = value
      }
      return acc
    }, {} as Record<string, string>)

    for (const file of files.value) {
      const fileContent = await readFileContent(file)
      await documentsStore.createDocument({
        content: fileContent,
        filename: file.name,
        collection: collection.value || undefined,
        metadata: Object.keys(metadataObj).length > 0 ? metadataObj : undefined
      })
    }

    success.value = true
    setTimeout(() => {
      router.push('/documents')
    }, 1500)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to upload files'
  } finally {
    loading.value = false
  }
}

async function submitUrl() {
  if (!url.value.trim()) {
    error.value = 'URL is required'
    return
  }

  // Basic URL validation
  try {
    new URL(url.value)
  } catch {
    error.value = 'Please enter a valid URL'
    return
  }

  loading.value = true
  error.value = null

  try {
    const metadataObj = metadata.value.reduce((acc, { key, value }) => {
      if (key.trim()) {
        acc[key.trim()] = value
      }
      return acc
    }, {} as Record<string, string>)

    await ingestionApi.ingestUrl(url.value, {
      collection: collection.value || undefined,
      metadata: Object.keys(metadataObj).length > 0 ? metadataObj : undefined
    })

    success.value = true
    setTimeout(() => {
      router.push('/documents')
    }, 1500)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to ingest URL'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (tab.value === 'text') {
    await submitText()
  } else if (tab.value === 'file') {
    await submitFiles()
  } else if (tab.value === 'url') {
    await submitUrl()
  }
}

function reset() {
  content.value = ''
  collection.value = ''
  metadata.value = []
  files.value = []
  url.value = ''
  error.value = null
  success.value = false
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <v-btn icon variant="text" @click="router.push('/documents')">
        <v-icon>mdi-arrow-left</v-icon>
      </v-btn>
      <h1 class="text-h4 ml-2">Upload Document</h1>
    </div>

    <v-card>
      <v-tabs v-model="tab">
        <v-tab value="text">Text Content</v-tab>
        <v-tab value="file">File Upload</v-tab>
        <v-tab value="url">URL</v-tab>
      </v-tabs>

      <v-card-text>
        <v-alert v-if="error" type="error" class="mb-4" closable @click:close="error = null">
          {{ error }}
        </v-alert>

        <v-alert v-if="success" type="success" class="mb-4">
          Document created successfully! Redirecting...
        </v-alert>

        <v-window v-model="tab">
          <!-- Text Content Tab -->
          <v-window-item value="text">
            <v-textarea
              v-model="content"
              label="Content"
              placeholder="Paste your document content here..."
              rows="10"
              :disabled="loading || success"
            ></v-textarea>
          </v-window-item>

          <!-- File Upload Tab -->
          <v-window-item value="file">
            <input
              ref="fileInputRef"
              type="file"
              multiple
              accept=".txt,.md,.json,.yaml,.yml,.xml,.csv,.html,.htm"
              style="display: none"
              @change="onFileSelect"
            />

            <!-- Drop Zone -->
            <div
              class="drop-zone pa-8 mb-4 text-center rounded-lg"
              :class="{ 'drop-zone-active': isDragging }"
              @dragover="onDragOver"
              @dragleave="onDragLeave"
              @drop="onDrop"
              @click="triggerFileInput"
            >
              <v-icon size="48" color="grey">mdi-cloud-upload</v-icon>
              <p class="text-h6 mt-4">Drag & drop files here</p>
              <p class="text-body-2 text-grey">or click to browse</p>
              <p class="text-caption text-grey mt-2">
                Supported: .txt, .md, .json, .yaml, .yml, .xml, .csv, .html
              </p>
            </div>

            <!-- File List -->
            <v-list v-if="files.length > 0" density="compact">
              <v-list-item
                v-for="(file, index) in files"
                :key="index"
                :title="file.name"
                :subtitle="formatFileSize(file.size)"
              >
                <template v-slot:prepend>
                  <v-icon>mdi-file-document</v-icon>
                </template>
                <template v-slot:append>
                  <v-btn
                    icon
                    size="small"
                    variant="text"
                    @click.stop="removeFile(index)"
                    :disabled="loading || success"
                  >
                    <v-icon>mdi-close</v-icon>
                  </v-btn>
                </template>
              </v-list-item>
            </v-list>
          </v-window-item>

          <!-- URL Tab -->
          <v-window-item value="url">
            <v-text-field
              v-model="url"
              label="URL"
              placeholder="https://example.com/document.html"
              prepend-inner-icon="mdi-link"
              :disabled="loading || success"
              hint="Enter a URL to fetch and ingest content from"
              persistent-hint
            ></v-text-field>
          </v-window-item>
        </v-window>

        <v-text-field
          v-model="collection"
          label="Collection (optional)"
          placeholder="default"
          :disabled="loading || success"
          class="mt-4"
        ></v-text-field>

        <!-- Metadata -->
        <div class="mt-4">
          <div class="d-flex align-center mb-2">
            <span class="text-subtitle-1">Metadata (optional)</span>
            <v-spacer></v-spacer>
            <v-btn size="small" variant="text" @click="addMetadata" :disabled="loading || success">
              <v-icon start>mdi-plus</v-icon>
              Add Field
            </v-btn>
          </div>

          <v-row v-for="(item, index) in metadata" :key="index" dense>
            <v-col cols="5">
              <v-text-field
                v-model="item.key"
                label="Key"
                density="compact"
                :disabled="loading || success"
              ></v-text-field>
            </v-col>
            <v-col cols="5">
              <v-text-field
                v-model="item.value"
                label="Value"
                density="compact"
                :disabled="loading || success"
              ></v-text-field>
            </v-col>
            <v-col cols="2">
              <v-btn icon size="small" variant="text" @click="removeMetadata(index)" :disabled="loading || success">
                <v-icon>mdi-delete</v-icon>
              </v-btn>
            </v-col>
          </v-row>
        </div>
      </v-card-text>

      <v-card-actions>
        <v-spacer></v-spacer>
        <v-btn @click="reset" :disabled="loading">Reset</v-btn>
        <v-btn color="primary" @click="submit" :loading="loading" :disabled="success || (tab === 'file' && files.length === 0)">
          {{ tab === 'file' ? `Upload ${files.length} File${files.length !== 1 ? 's' : ''}` : tab === 'url' ? 'Ingest URL' : 'Create Document' }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </div>
</template>

<style scoped>
.drop-zone {
  border: 2px dashed #ccc;
  cursor: pointer;
  transition: all 0.3s ease;
  background-color: #fafafa;
}

.drop-zone:hover {
  border-color: #1976d2;
  background-color: #e3f2fd;
}

.drop-zone-active {
  border-color: #1976d2;
  background-color: #e3f2fd;
}
</style>
