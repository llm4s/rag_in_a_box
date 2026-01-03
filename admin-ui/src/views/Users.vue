<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import type { User } from '@/types/api'
import * as usersApi from '@/api/users'

const users = ref<User[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// Create user dialog
const createDialog = ref(false)
const createForm = ref({
  username: '',
  password: '',
  role: 'user'
})
const createLoading = ref(false)

// Reset password dialog
const resetDialog = ref(false)
const resetUserId = ref<number | null>(null)
const resetPassword = ref('')
const resetLoading = ref(false)

// Delete confirmation
const deleteDialog = ref(false)
const deleteUserId = ref<number | null>(null)
const deleteLoading = ref(false)

const roleOptions = [
  { title: 'User', value: 'user' },
  { title: 'Admin', value: 'admin' }
]

const userToDelete = computed(() => {
  if (!deleteUserId.value) return null
  return users.value.find(u => u.id === deleteUserId.value)
})

async function loadUsers() {
  loading.value = true
  error.value = null
  try {
    const response = await usersApi.getUsers()
    users.value = response.users
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to load users'
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.value = { username: '', password: '', role: 'user' }
  createDialog.value = true
}

async function handleCreate() {
  if (!createForm.value.username || !createForm.value.password) return

  createLoading.value = true
  try {
    await usersApi.createUser({
      username: createForm.value.username,
      password: createForm.value.password,
      role: createForm.value.role
    })
    createDialog.value = false
    await loadUsers()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to create user'
  } finally {
    createLoading.value = false
  }
}

function openResetDialog(userId: number) {
  resetUserId.value = userId
  resetPassword.value = ''
  resetDialog.value = true
}

async function handleResetPassword() {
  if (!resetUserId.value || !resetPassword.value) return

  resetLoading.value = true
  try {
    await usersApi.resetPassword(resetUserId.value, resetPassword.value)
    resetDialog.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to reset password'
  } finally {
    resetLoading.value = false
  }
}

function openDeleteDialog(userId: number) {
  deleteUserId.value = userId
  deleteDialog.value = true
}

async function handleDelete() {
  if (!deleteUserId.value) return

  deleteLoading.value = true
  try {
    await usersApi.deleteUser(deleteUserId.value)
    deleteDialog.value = false
    await loadUsers()
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to delete user'
  } finally {
    deleteLoading.value = false
  }
}

function getRoleColor(role: string) {
  return role === 'admin' ? 'error' : 'primary'
}

onMounted(() => {
  loadUsers()
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-6">
      <h1 class="text-h4">Users</h1>
      <v-spacer></v-spacer>
      <v-btn color="primary" @click="openCreateDialog">
        <v-icon start>mdi-plus</v-icon>
        Add User
      </v-btn>
    </div>

    <v-alert v-if="error" type="error" closable class="mb-4" @click:close="error = null">
      {{ error }}
    </v-alert>

    <v-card>
      <v-card-title class="d-flex align-center">
        <span>System Users</span>
        <v-spacer></v-spacer>
        <v-btn icon size="small" @click="loadUsers" :loading="loading">
          <v-icon>mdi-refresh</v-icon>
        </v-btn>
      </v-card-title>

      <v-skeleton-loader v-if="loading && !users.length" type="table"></v-skeleton-loader>

      <v-table v-else-if="users.length" density="comfortable">
        <thead>
          <tr>
            <th>ID</th>
            <th>Username</th>
            <th>Role</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>{{ user.id }}</td>
            <td>
              <div class="d-flex align-center">
                <v-icon class="mr-2" size="small">mdi-account</v-icon>
                {{ user.username }}
              </div>
            </td>
            <td>
              <v-chip :color="getRoleColor(user.role)" size="small">
                {{ user.role }}
              </v-chip>
            </td>
            <td>
              <v-btn
                icon
                size="small"
                variant="text"
                @click="openResetDialog(user.id)"
              >
                <v-icon>mdi-key</v-icon>
                <v-tooltip activator="parent" location="top">Reset Password</v-tooltip>
              </v-btn>
              <v-btn
                icon
                size="small"
                variant="text"
                color="error"
                @click="openDeleteDialog(user.id)"
                :disabled="user.role === 'admin' && users.filter(u => u.role === 'admin').length === 1"
              >
                <v-icon>mdi-delete</v-icon>
                <v-tooltip activator="parent" location="top">Delete User</v-tooltip>
              </v-btn>
            </td>
          </tr>
        </tbody>
      </v-table>

      <div v-else class="text-center text-grey py-8">
        No users found
      </div>
    </v-card>

    <!-- Create User Dialog -->
    <v-dialog v-model="createDialog" max-width="400">
      <v-card>
        <v-card-title>Create User</v-card-title>
        <v-card-text>
          <v-text-field
            v-model="createForm.username"
            label="Username"
            variant="outlined"
            class="mb-3"
            autofocus
          ></v-text-field>
          <v-text-field
            v-model="createForm.password"
            label="Password"
            type="password"
            variant="outlined"
            class="mb-3"
          ></v-text-field>
          <v-select
            v-model="createForm.role"
            :items="roleOptions"
            label="Role"
            variant="outlined"
          ></v-select>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="createDialog = false">Cancel</v-btn>
          <v-btn
            color="primary"
            @click="handleCreate"
            :loading="createLoading"
            :disabled="!createForm.username || !createForm.password"
          >
            Create
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Reset Password Dialog -->
    <v-dialog v-model="resetDialog" max-width="400">
      <v-card>
        <v-card-title>Reset Password</v-card-title>
        <v-card-text>
          <v-text-field
            v-model="resetPassword"
            label="New Password"
            type="password"
            variant="outlined"
            autofocus
          ></v-text-field>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn @click="resetDialog = false">Cancel</v-btn>
          <v-btn
            color="primary"
            @click="handleResetPassword"
            :loading="resetLoading"
            :disabled="!resetPassword"
          >
            Reset
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Delete Confirmation Dialog -->
    <v-dialog v-model="deleteDialog" max-width="400">
      <v-card>
        <v-card-title>Delete User</v-card-title>
        <v-card-text>
          Are you sure you want to delete user <strong>{{ userToDelete?.username }}</strong>?
          This action cannot be undone.
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
