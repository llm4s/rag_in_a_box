<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SwaggerUI from 'swagger-ui-dist/swagger-ui-es-bundle.js'
import 'swagger-ui-dist/swagger-ui.css'

const swaggerContainer = ref<HTMLElement | null>(null)

onMounted(() => {
  if (swaggerContainer.value) {
    SwaggerUI({
      domNode: swaggerContainer.value,
      url: '/admin/openapi.yaml',
      deepLinking: true,
      presets: [
        SwaggerUI.presets.apis,
        SwaggerUI.SwaggerUIStandalonePreset
      ],
      layout: 'BaseLayout',
      defaultModelsExpandDepth: 1,
      defaultModelExpandDepth: 1,
      docExpansion: 'list',
      filter: true,
      showExtensions: true,
      showCommonExtensions: true,
    })
  }
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-4">
      <h1 class="text-h4">API Documentation</h1>
      <v-spacer />
      <v-btn
        variant="outlined"
        size="small"
        href="/admin/openapi.yaml"
        target="_blank"
        prepend-icon="mdi-download"
      >
        Download OpenAPI Spec
      </v-btn>
    </div>

    <v-card>
      <div ref="swaggerContainer" class="swagger-container" />
    </v-card>
  </div>
</template>

<style>
.swagger-container {
  padding: 16px;
}

/* Override Swagger UI styles for better integration */
.swagger-ui .topbar {
  display: none;
}

.swagger-ui .info {
  margin: 20px 0;
}

.swagger-ui .scheme-container {
  background: transparent;
  box-shadow: none;
  padding: 0;
}
</style>
