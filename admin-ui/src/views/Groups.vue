<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import type { Principal } from '@/types/api'
import * as principalsApi from '@/api/principals'

const userPrincipals = ref<Principal[]>([])
const groupPrincipals = ref<Principal[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const activeTab = ref('groups')

// Create dialog
const createDialog = ref(false)
const createType = ref<'user' | 'group'>('group')
const createExternalId = ref('')
const createLoading = ref(false)

// Delete confirmation
const deleteDialog = ref(false)
const deletePrincipal = ref<Principal | null>(null)
const deleteLoading = ref(false)

const currentList = computed(() => {
  return activeTab.value === 'groups' ? groupPrincipals.value : userPrincipals.value
})

const currentType = computed(() => {
  return activeTab.value === 'groups' ? 'group' : 'user'
})

async function loadPrincipals() {
  loading.value = true
  error.value = null
  try {
    const [users, groups] = await Promise.all([
      principalsApi.getUsers(),
      principalsApi.getGroups()
    ])
    userPrincipals.value = users.principals
    groupPrincipals.value = groups.principals
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load principals'
  } finally {
    loading.value = false
  }
}

function openCreateDialog(type: 'user' | 'group') {
  createType.value = type
  createExternalId.value = ''
  createDialog.value = true
}

async function handleCreate() {
  if (!createExternalId.value) return

  createLoading.value = true
  try {
    if (createType.value === 'group') {
      await principalsApi.createGroup(createExternalId.value)
    } else {
      await principalsApi.createUser(createExternalId.value)
    }
    createDialog.value = false
    await loadPrincipals()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to create principal'
  } finally {
    createLoading.value = false
  }
}

function openDeleteDialog(principal: Principal) {
  deletePrincipal.value = principal
  deleteDialog.value = true
}

async function handleDelete() {
  if (!deletePrincipal.value) return

  deleteLoading.value = true
  try {
    const type = deletePrincipal.value.principalType === 'group' ? 'groups' : 'users'
    // Extract the external ID without the type prefix
    const externalId = deletePrincipal.value.externalId.replace(/^(user|group):/, '')
    await principalsApi.deletePrincipal(type, externalId)
    deleteDialog.value = false
    await loadPrincipals()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to delete principal'
  } finally {
    deleteLoading.value = false
  }
}

function getTypeIcon(type: string) {
  return type === 'group' ? 'mdi-account-group' : 'mdi-account'
}

function getTypeColor(type: string) {
  return type === 'group' ? 'secondary' : 'primary'
}

onMounted(() => {
  loadPrincipals()
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Principals</h1>
      <v-spacer></v-spacer>
      <v-btn color="primary" @click="openCreateDialog(currentType as 'user' | 'group')">
        <v-icon start>mdi-plus</v-icon>
        Add {{ currentType === 'group' ? 'Group' : 'User Principal' }}
      </v-btn>
    </div>

    <v-alert v-if="error" type="error" closable class="mb-4" @click:close="error = null">
      {{ error }}
    </v-alert>

    <v-card>
      <v-tabs v-model="activeTab" color="primary">
        <v-tab value="groups">
          <v-icon start>mdi-account-group</v-icon>
          Groups ({{ groupPrincipals.length }})
        </v-tab>
        <v-tab value="users">
          <v-icon start>mdi-account</v-icon>
          User Principals ({{ userPrincipals.length }})
        </v-tab>
      </v-tabs>

      <v-divider></v-divider>

      <div class="pa-4">
        <div class="d-flex align-center mb-4">
          <span class="text-subtitle-1 text-grey">
            {{ activeTab === 'groups'
              ? 'Groups are used to manage permissions for multiple users at once.'
              : 'User principals represent external user identities for permission checks.'
            }}
          </span>
          <v-spacer></v-spacer>
          <v-btn icon size="small" @click="loadPrincipals" :loading="loading">
            <v-icon>mdi-refresh</v-icon>
          </v-btn>
        </div>

        <v-skeleton-loader v-if="loading && !currentList.length" type="list-item@3"></v-skeleton-loader>

        <v-list v-else-if="currentList.length" density="compact">
          <v-list-item
            v-for="principal in currentList"
            :key="principal.id"
          >
            <template v-slot:prepend>
              <v-icon :color="getTypeColor(principal.principalType)">
                {{ getTypeIcon(principal.principalType) }}
              </v-icon>
            </template>

            <v-list-item-title>{{ principal.externalId }}</v-list-item-title>
            <v-list-item-subtitle>ID: {{ principal.id }}</v-list-item-subtitle>

            <template v-slot:append>
              <v-btn
                icon
                size="small"
                variant="text"
                color="error"
                @click="openDeleteDialog(principal)"
              >
                <v-icon>mdi-delete</v-icon>
                <v-tooltip activator="parent" location="top">Delete</v-tooltip>
              </v-btn>
            </template>
          </v-list-item>
        </v-list>

        <div v-else class="text-center text-grey py-8">
          No {{ activeTab === 'groups' ? 'groups' : 'user principals' }} found
        </div>
      </div>
    </v-card>

    <!-- Create Dialog -->
    <v-dialog v-model="createDialog" max-width="400">
      <v-card>
        <v-card-title>
          Create {{ createType === 'group' ? 'Group' : 'User Principal' }}
        </v-card-title>
        <v-card-text>
          <v-text-field
            v-model="createExternalId"
            :label="createType === 'group' ? 'Group Name' : 'User External ID'"
            :placeholder="createType === 'group' ? 'e.g., engineering, sales' : 'e.g., alice, bob'"
            variant="outlined"
            autofocus
          ></v-text-field>
          <p class="text-caption text-grey mt-2">
            {{ createType === 'group'
              ? 'This creates a group that can be assigned permissions on collections.'
              : 'This creates a user principal for permission-based access control.'
            }}
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="createDialog = false">Cancel</v-btn>
          <v-btn
            color="primary"
            @click="handleCreate"
            :loading="createLoading"
            :disabled="!createExternalId"
          >
            Create
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="400">
      <v-card>
        <v-card-title>Delete Principal</v-card-title>
        <v-card-text>
          Are you sure you want to delete <strong>{{ deletePrincipal?.externalId }}</strong>?
          This will remove it from all collection permissions.
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
