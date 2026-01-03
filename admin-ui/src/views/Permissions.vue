<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import type { CollectionPermission, Principal } from '@/types/api'
import * as permissionsApi from '@/api/permissions'
import * as principalsApi from '@/api/principals'

const collections = ref<CollectionPermission[]>([])
const allPrincipals = ref<Principal[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// Create collection dialog
const createDialog = ref(false)
const createForm = ref({
  path: '',
  isLeaf: true,
  queryableBy: [] as string[]
})
const createLoading = ref(false)

// Edit permissions dialog
const editDialog = ref(false)
const editCollection = ref<CollectionPermission | null>(null)
const editPermissions = ref<string[]>([])
const editLoading = ref(false)

// Delete confirmation
const deleteDialog = ref(false)
const deleteCollection = ref<CollectionPermission | null>(null)
const deleteLoading = ref(false)

const principalOptions = computed(() => {
  return allPrincipals.value.map(p => ({
    title: p.externalId,
    value: p.externalId
  }))
})

// Create a map of principal ID to external ID for display
const principalIdToName = computed(() => {
  const map = new Map<number, string>()
  allPrincipals.value.forEach(p => {
    map.set(p.id, p.externalId)
  })
  return map
})

async function loadData() {
  loading.value = true
  error.value = null
  try {
    const [collectionsRes, users, groups] = await Promise.all([
      permissionsApi.getCollections(),
      principalsApi.getUsers(),
      principalsApi.getGroups()
    ])
    collections.value = collectionsRes.collections
    allPrincipals.value = [...users.principals, ...groups.principals]
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load data'
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.value = { path: '', isLeaf: true, queryableBy: [] }
  createDialog.value = true
}

async function handleCreate() {
  if (!createForm.value.path) return

  createLoading.value = true
  try {
    await permissionsApi.createCollection({
      path: createForm.value.path,
      isLeaf: createForm.value.isLeaf,
      queryableBy: createForm.value.queryableBy.length > 0 ? createForm.value.queryableBy : undefined
    })
    createDialog.value = false
    await loadData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to create collection'
  } finally {
    createLoading.value = false
  }
}

function openEditDialog(collection: CollectionPermission) {
  editCollection.value = collection
  // Convert principal IDs back to external IDs
  editPermissions.value = collection.queryableBy
    .map(id => principalIdToName.value.get(id))
    .filter((name): name is string => name !== undefined)
  editDialog.value = true
}

async function handleUpdatePermissions() {
  if (!editCollection.value) return

  editLoading.value = true
  try {
    await permissionsApi.updatePermissions(editCollection.value.path, {
      queryableBy: editPermissions.value
    })
    editDialog.value = false
    await loadData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to update permissions'
  } finally {
    editLoading.value = false
  }
}

function openDeleteDialog(collection: CollectionPermission) {
  deleteCollection.value = collection
  deleteDialog.value = true
}

async function handleDelete() {
  if (!deleteCollection.value) return

  deleteLoading.value = true
  try {
    await permissionsApi.deleteCollection(deleteCollection.value.path)
    deleteDialog.value = false
    await loadData()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to delete collection'
  } finally {
    deleteLoading.value = false
  }
}

function getPrincipalNames(ids: number[]): string[] {
  return ids
    .map(id => principalIdToName.value.get(id))
    .filter((name): name is string => name !== undefined)
}

function getAccessLabel(collection: CollectionPermission): string {
  if (collection.isPublic) return 'Public'
  if (collection.queryableBy.length === 0) return 'No access defined'
  return `${collection.queryableBy.length} principal(s)`
}

function getAccessColor(collection: CollectionPermission): string {
  if (collection.isPublic) return 'success'
  if (collection.queryableBy.length === 0) return 'warning'
  return 'primary'
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Collection Permissions</h1>
      <v-spacer></v-spacer>
      <v-btn color="primary" @click="openCreateDialog">
        <v-icon start>mdi-plus</v-icon>
        Add Collection
      </v-btn>
    </div>

    <v-alert v-if="error" type="error" closable class="mb-4" @click:close="error = null">
      {{ error }}
    </v-alert>

    <v-card>
      <v-card-title class="d-flex align-center">
        <span>Collections</span>
        <v-spacer></v-spacer>
        <v-btn icon size="small" @click="loadData" :loading="loading">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </v-card-title>

      <v-skeleton-loader v-if="loading && !collections.length" type="table"></v-skeleton-loader>

      <v-table v-else-if="collections.length" density="comfortable">
        <thead>
          <tr>
            <th>Path</th>
            <th>Type</th>
            <th>Access</th>
            <th>Queryable By</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="collection in collections" :key="collection.id">
            <td>
              <div class="d-flex align-center">
                <v-icon class="mr-2" size="small">
                  {{ collection.isLeaf ? 'mdi-folder' : 'mdi-folder-multiple' }}
                </v-icon>
                <code>{{ collection.path }}</code>
              </div>
            </td>
            <td>
              <v-chip size="x-small" variant="outlined">
                {{ collection.isLeaf ? 'Leaf' : 'Parent' }}
              </v-chip>
            </td>
            <td>
              <v-chip :color="getAccessColor(collection)" size="small">
                {{ getAccessLabel(collection) }}
              </v-chip>
            </td>
            <td>
              <div class="d-flex flex-wrap gap-1">
                <v-chip
                  v-for="name in getPrincipalNames(collection.queryableBy)"
                  :key="name"
                  size="x-small"
                  variant="outlined"
                >
                  {{ name }}
                </v-chip>
                <span v-if="collection.queryableBy.length === 0" class="text-grey text-caption">
                  {{ collection.isPublic ? 'Everyone' : 'None' }}
                </span>
              </div>
            </td>
            <td>
              <v-btn
                icon
                size="small"
                variant="text"
                @click="openEditDialog(collection)"
              >
                <v-icon>mdi-pencil</v-icon>
                <v-tooltip activator="parent" location="top">Edit Permissions</v-tooltip>
              </v-btn>
              <v-btn
                icon
                size="small"
                variant="text"
                color="error"
                @click="openDeleteDialog(collection)"
              >
                <v-icon>mdi-delete</v-icon>
                <v-tooltip activator="parent" location="top">Delete</v-tooltip>
              </v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>

      <div v-else class="text-center text-grey py-8">
        <v-icon size="48" class="mb-4">mdi-folder-lock-open</v-icon>
        <div>No collections with permissions defined</div>
        <div class="text-caption">Create a collection to start managing permissions</div>
      </div>
    </v-card>

    <!-- Create Collection Dialog -->
    <v-dialog v-model="createDialog" max-width="500">
      <v-card>
        <v-card-title>Create Collection</v-card-title>
        <v-card-text>
          <v-text-field
            v-model="createForm.path"
            label="Collection Path"
            placeholder="e.g., docs, company/engineering"
            variant="outlined"
            class="mb-3"
            autofocus
          ></v-text-field>

          <v-switch
            v-model="createForm.isLeaf"
            label="Leaf collection (can contain documents)"
            color="primary"
            hide-details
            class="mb-3"
          ></v-switch>

          <v-select
            v-model="createForm.queryableBy"
            :items="principalOptions"
            label="Queryable By (optional)"
            variant="outlined"
            multiple
            chips
            closable-chips
          >
            <template v-slot:no-data>
              <div class="pa-4 text-center text-grey">
                No principals available. Create groups or user principals first.
              </div>
            </template>
          </v-select>

          <p class="text-caption text-grey mt-2">
            Leave "Queryable By" empty for public access, or select principals to restrict access.
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="createDialog = false">Cancel</v-btn>
          <v-btn
            color="primary"
            @click="handleCreate"
            :loading="createLoading"
            :disabled="!createForm.path"
          >
            Create
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Edit Permissions Dialog -->
    <v-dialog v-model="editDialog" max-width="500">
      <v-card>
        <v-card-title>Edit Permissions</v-card-title>
        <v-card-subtitle>
          <code>{{ editCollection?.path }}</code>
        </v-card-subtitle>
        <v-card-text>
          <v-select
            v-model="editPermissions"
            :items="principalOptions"
            label="Queryable By"
            variant="outlined"
            multiple
            chips
            closable-chips
          >
            <template v-slot:no-data>
              <div class="pa-4 text-center text-grey">
                No principals available. Create groups or user principals first.
              </div>
            </template>
          </v-select>

          <v-alert type="info" density="compact" class="mt-3">
            <template v-slot:text>
              <div v-if="editPermissions.length === 0">
                This collection will be <strong>publicly accessible</strong> to all users.
              </div>
              <div v-else>
                Only users in the selected groups/principals can query this collection.
              </div>
            </template>
          </v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="editDialog = false">Cancel</v-btn>
          <v-btn
            color="primary"
            @click="handleUpdatePermissions"
            :loading="editLoading"
          >
            Save
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="400">
      <v-card>
        <v-card-title>Delete Collection</v-card-title>
        <v-card-text>
          Are you sure you want to delete collection <code>{{ deleteCollection?.path }}</code>?
          This will remove all permission settings for this collection.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="deleteDialog = false">Cancel</v-btn>
          <v-btn
            color="error"
            @click="handleDelete"
            :loading="deleteLoading"
          >
            Delete
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
