import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/views/Dashboard.vue'),
      meta: { title: 'Dashboard' }
    },
    {
      path: '/documents',
      name: 'documents',
      component: () => import('@/views/Documents.vue'),
      meta: { title: 'Documents' }
    },
    {
      path: '/documents/upload',
      name: 'upload',
      component: () => import('@/views/Upload.vue'),
      meta: { title: 'Upload Document' }
    },
    {
      path: '/documents/:id',
      name: 'document-detail',
      component: () => import('@/views/DocumentDetail.vue'),
      meta: { title: 'Document Detail' }
    },
    {
      path: '/config',
      name: 'config',
      component: () => import('@/views/Config.vue'),
      meta: { title: 'Configuration' }
    },
    {
      path: '/config/runtime',
      name: 'runtime-config',
      component: () => import('@/views/RuntimeConfig.vue'),
      meta: { title: 'Runtime Configuration' }
    },
    {
      path: '/config/collections',
      name: 'collection-config',
      component: () => import('@/views/CollectionConfig.vue'),
      meta: { title: 'Collection Configuration' }
    },
    {
      path: '/chunking',
      name: 'chunking',
      component: () => import('@/views/ChunkingPreview.vue'),
      meta: { title: 'Chunking Preview' }
    },
    {
      path: '/visibility',
      name: 'visibility',
      component: () => import('@/views/Visibility.vue'),
      meta: { title: 'System Visibility' }
    },
    {
      path: '/ingestion',
      name: 'ingestion',
      component: () => import('@/views/Ingestion.vue'),
      meta: { title: 'Ingestion' }
    },
    {
      path: '/api-docs',
      name: 'api-docs',
      component: () => import('@/views/ApiDocs.vue'),
      meta: { title: 'API Documentation' }
    },
  ],
})

router.beforeEach((to, _from, next) => {
  document.title = `${to.meta.title || 'Admin'} - RAG in a Box`
  next()
})

export default router
