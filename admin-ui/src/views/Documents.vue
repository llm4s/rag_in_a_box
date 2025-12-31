<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDocumentsStore } from '@/stores/documents'
import ErrorAlert from '@/components/ErrorAlert.vue'
import DocumentPreviewDialog from '@/components/DocumentPreviewDialog.vue'
import { TableSkeleton } from '@/components/skeletons'

const router = useRouter()
const documentsStore = useDocumentsStore()

const isInitialLoad = computed(() =>
  documentsStore.loading && documentsStore.documents.length === 0
)

const search = ref('')
const collection = ref('')
const deleteDialog = ref(false)
const deleteId = ref<string | null>(null)
const selected = ref<string[]>([])
const bulkDeleteDialog = ref(false)
const bulkDeleting = ref(false)
const previewDialog = ref(false)
const previewDocumentId = ref<string | null>(null)

const headers = [
  { title: 'ID', key: 'id', width: '200px' },
  { title: 'Filename', key: 'filename' },
  { title: 'Collection', key: 'collection' },
  { title: 'Chunks', key: 'chunkCount', width: '100px' },
  { title: 'Actions', key: 'actions', sortable: false, width: '160px' }
]

const hasSelection = computed(() => selected.value.length > 0)

onMounted(() => {
  documentsStore.fetchDocuments()
})

watch([search, collection], () => {
  documentsStore.setPage(1)
  documentsStore.fetchDocuments({ search: search.value, collection: collection.value })
})

function viewDocument(id: string) {
  router.push(`/documents/${id}`)
}

function previewDocument(id: string) {
  previewDocumentId.value = id
  previewDialog.value = true
}

function confirmDelete(id: string) {
  deleteId.value = id
  deleteDialog.value = true
}

async function deleteDocument() {
  if (deleteId.value) {
    await documentsStore.deleteDocument(deleteId.value)
    deleteDialog.value = false
    deleteId.value = null
  }
}

async function bulkDelete() {
  if (selected.value.length === 0) return

  bulkDeleting.value = true
  try {
    // Delete documents one by one
    for (const id of selected.value) {
      await documentsStore.deleteDocument(id)
    }
    selected.value = []
    bulkDeleteDialog.value = false
  } finally {
    bulkDeleting.value = false
  }
}

function clearSelection() {
  selected.value = []
}

function onPageChange(page: number) {
  documentsStore.setPage(page)
  documentsStore.fetchDocuments({ search: search.value, collection: collection.value })
}
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Documents</h1>
      <v-spacer></v-spacer>
      <v-btn color="primary" prepend-icon="mdi-upload" @click="router.push('/documents/upload')">
        Upload
      </v-btn>
    </div>

    <!-- Bulk Actions Bar -->
    <v-slide-y-transition>
      <v-card v-if="hasSelection" class="mb-4 pa-3 bg-primary" variant="flat">
        <div class="d-flex align-center text-white">
          <v-icon class="mr-2">mdi-checkbox-marked</v-icon>
          <span class="font-weight-medium">{{ selected.length }} document{{ selected.length === 1 ? '' : 's' }} selected</span>
          <v-spacer></v-spacer>
          <v-btn
            variant="outlined"
            size="small"
            class="mr-2"
            @click="clearSelection"
          >
            Clear
          </v-btn>
          <v-btn
            color="error"
            variant="flat"
            size="small"
            prepend-icon="mdi-delete"
            @click="bulkDeleteDialog = true"
          >
            Delete Selected
          </v-btn>
        </div>
      </v-card>
    </v-slide-y-transition>

    <!-- Error Alert -->
    <ErrorAlert
      :error="documentsStore.error"
      :on-retry="documentsStore.retryFetchDocuments"
      dismissible
      @dismiss="documentsStore.clearError"
    />

    <!-- Filters -->
    <v-card class="mb-4 pa-4">
      <v-row>
        <v-col cols="12" sm="6">
          <v-text-field
            v-model="search"
            label="Search"
            prepend-inner-icon="mdi-magnify"
            clearable
            hide-details
          ></v-text-field>
        </v-col>
        <v-col cols="12" sm="6">
          <v-text-field
            v-model="collection"
            label="Collection"
            prepend-inner-icon="mdi-folder"
            clearable
            hide-details
          ></v-text-field>
        </v-col>
      </v-row>
    </v-card>

    <!-- Documents Table - Skeleton -->
    <TableSkeleton v-if="isInitialLoad" :rows="5" :columns="5" />

    <!-- Documents Table -->
    <v-card v-else>
      <v-data-table
        v-model="selected"
        :headers="headers"
        :items="documentsStore.documents"
        :loading="documentsStore.loading"
        :items-per-page="documentsStore.pageSize"
        item-value="id"
        show-select
        hide-default-footer
      >
        <template v-slot:item.id="{ item }">
          <code class="text-caption">{{ item.id.substring(0, 8) }}...</code>
        </template>

        <template v-slot:item.filename="{ item }">
          {{ item.filename || '(untitled)' }}
        </template>

        <template v-slot:item.collection="{ item }">
          <v-chip size="small" color="primary" variant="outlined">
            {{ item.collection || 'default' }}
          </v-chip>
        </template>

        <template v-slot:item.actions="{ item }">
          <v-btn icon size="small" variant="text" @click="previewDocument(item.id)" title="Quick Preview">
            <v-icon>mdi-eye-outline</v-icon>
          </v-btn>
          <v-btn icon size="small" variant="text" @click="viewDocument(item.id)" title="View Details">
            <v-icon>mdi-open-in-new</v-icon>
          </v-btn>
          <v-btn icon size="small" variant="text" color="error" @click="confirmDelete(item.id)" title="Delete">
            <v-icon>mdi-delete</v-icon>
          </v-btn>
        </template>

        <template v-slot:no-data>
          <div class="text-center pa-8">
            <v-icon size="64" color="grey">mdi-file-document-outline</v-icon>
            <p class="text-h6 mt-4">No documents found</p>
            <v-btn color="primary" class="mt-4" @click="router.push('/documents/upload')">
              Upload your first document
            </v-btn>
          </div>
        </template>
      </v-data-table>

      <!-- Pagination -->
      <div class="d-flex justify-center pa-4" v-if="documentsStore.totalPages > 1">
        <v-pagination
          :length="documentsStore.totalPages"
          :model-value="documentsStore.page"
          @update:model-value="onPageChange"
        ></v-pagination>
      </div>
    </v-card>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="400">
      <v-card>
        <v-card-title>Delete Document</v-card-title>
        <v-card-text>
          Are you sure you want to delete this document? This action cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="deleteDialog = false">Cancel</v-btn>
          <v-btn color="error" @click="deleteDocument">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Bulk Delete Confirmation Dialog -->
    <v-dialog v-model="bulkDeleteDialog" max-width="450">
      <v-card>
        <v-card-title class="d-flex align-center">
          <v-icon color="error" class="mr-2">mdi-alert</v-icon>
          Delete {{ selected.length }} Document{{ selected.length === 1 ? '' : 's' }}
        </v-card-title>
        <v-card-text>
          <p>Are you sure you want to delete <strong>{{ selected.length }}</strong> document{{ selected.length === 1 ? '' : 's' }}?</p>
          <p class="text-error mt-2">This action cannot be undone.</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="bulkDeleteDialog = false" :disabled="bulkDeleting">Cancel</v-btn>
          <v-btn
            color="error"
            @click="bulkDelete"
            :loading="bulkDeleting"
          >
            Delete All
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Document Preview Dialog -->
    <DocumentPreviewDialog
      v-model="previewDialog"
      :document-id="previewDocumentId"
    />
  </div>
</template>
