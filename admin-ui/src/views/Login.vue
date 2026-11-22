<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getErrorMessage } from '@/composables/useApiError'
import { safeRedirect } from '@/router/guards'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const apiKey = ref('')
const error = ref<string | null>(null)
const busy = ref<'password' | 'api-key' | 'sso' | null>(null)

const showPasswordForm = computed(() => auth.hasMethod('password'))
const showSso = computed(() => auth.hasMethod('sso'))
const showApiKey = computed(() => auth.hasMethod('api-key'))
const noMethods = computed(() => !showPasswordForm.value && !showSso.value && !showApiKey.value)

/** Internal path to continue to after login (validated against the router). */
const target = computed(() => safeRedirect(router, route.query.redirect) ?? '/')

onMounted(() => {
  void auth.loadMode()
})

async function continueToTarget() {
  await router.replace(target.value)
}

async function submitPassword() {
  if (!username.value || !password.value) return
  error.value = null
  busy.value = 'password'
  try {
    await auth.loginWithPassword(username.value, password.value)
    password.value = ''
    await continueToTarget()
  } catch (e) {
    error.value = getErrorMessage(e)
  } finally {
    busy.value = null
  }
}

async function submitApiKey() {
  if (!apiKey.value.trim()) return
  error.value = null
  busy.value = 'api-key'
  try {
    await auth.loginWithApiKey(apiKey.value)
    apiKey.value = ''
    await continueToTarget()
  } catch (e) {
    error.value = getErrorMessage(e)
  } finally {
    busy.value = null
  }
}

function startSso() {
  error.value = null
  busy.value = 'sso'
  // Full-page redirect; the server sends the browser back to this path (with base, e.g. /admin/documents)
  auth.loginWithSso(router.resolve(target.value).href)
}
</script>

<template>
  <v-row justify="center" class="mt-4 mt-sm-12">
    <v-col cols="12" sm="8" md="6" lg="4">
      <v-card elevation="2">
        <v-card-title class="d-flex align-center pa-4">
          <v-icon color="primary" class="mr-2">mdi-lock</v-icon>
          <h1 class="text-h5">Sign in</h1>
        </v-card-title>
        <v-card-subtitle class="px-4">RAG in a Box administration</v-card-subtitle>

        <v-card-text class="pa-4">
          <v-alert
            v-if="error"
            type="error"
            variant="tonal"
            density="compact"
            class="mb-4"
            closable
            role="alert"
            @click:close="error = null"
          >
            {{ error }}
          </v-alert>

          <v-alert v-if="noMethods" type="warning" variant="tonal" density="compact">
            No sign-in method is available. Check the server's authentication configuration.
          </v-alert>

          <v-form v-if="showPasswordForm" @submit.prevent="submitPassword">
            <v-text-field
              v-model="username"
              label="Username"
              prepend-inner-icon="mdi-account"
              autocomplete="username"
              variant="outlined"
              density="comfortable"
              autofocus
              :disabled="busy !== null"
            ></v-text-field>
            <v-text-field
              v-model="password"
              label="Password"
              prepend-inner-icon="mdi-key"
              :type="showPassword ? 'text' : 'password'"
              :append-inner-icon="showPassword ? 'mdi-eye-off' : 'mdi-eye'"
              autocomplete="current-password"
              variant="outlined"
              density="comfortable"
              :disabled="busy !== null"
              @click:append-inner="showPassword = !showPassword"
            ></v-text-field>
            <v-btn
              type="submit"
              color="primary"
              block
              size="large"
              :loading="busy === 'password'"
              :disabled="!username || !password || (busy !== null && busy !== 'password')"
            >
              Sign in
            </v-btn>
          </v-form>

          <template v-if="showSso">
            <div v-if="showPasswordForm" class="d-flex align-center my-4">
              <v-divider></v-divider>
              <span class="mx-2 text-caption text-medium-emphasis">or</span>
              <v-divider></v-divider>
            </div>
            <v-btn
              color="primary"
              :variant="showPasswordForm ? 'outlined' : 'flat'"
              block
              size="large"
              prepend-icon="mdi-shield-account"
              :loading="busy === 'sso'"
              :disabled="busy !== null && busy !== 'sso'"
              @click="startSso"
            >
              Sign in with SSO
            </v-btn>
          </template>

          <template v-if="showApiKey">
            <div v-if="showPasswordForm || showSso" class="d-flex align-center my-4">
              <v-divider></v-divider>
              <span class="mx-2 text-caption text-medium-emphasis">or use an API key</span>
              <v-divider></v-divider>
            </div>
            <v-form @submit.prevent="submitApiKey">
              <v-text-field
                v-model="apiKey"
                label="API key"
                prepend-inner-icon="mdi-key-variant"
                type="password"
                autocomplete="off"
                variant="outlined"
                density="comfortable"
                :autofocus="!showPasswordForm && !showSso"
                :disabled="busy !== null"
              ></v-text-field>
              <v-btn
                type="submit"
                color="primary"
                :variant="showPasswordForm || showSso ? 'tonal' : 'flat'"
                block
                size="large"
                :loading="busy === 'api-key'"
                :disabled="!apiKey.trim() || (busy !== null && busy !== 'api-key')"
              >
                Use API key
              </v-btn>
            </v-form>
          </template>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>
</template>
