import { createRouter, createWebHistory } from 'vue-router'
import { createAuthGuard } from './guards'

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { title: 'Sign in', public: true }
    },
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
      meta: { title: 'Runtime Configuration', requiresAdmin: true }
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
      meta: { title: 'System Visibility', requiresAdmin: true }
    },
    {
      path: '/ingestion',
      name: 'ingestion',
      component: () => import('@/views/Ingestion.vue'),
      meta: { title: 'Ingestion', requiresAdmin: true }
    },
    {
      path: '/analytics',
      name: 'analytics',
      component: () => import('@/views/Analytics.vue'),
      meta: { title: 'Query Analytics', requiresAdmin: true }
    },
    {
      path: '/suggestions',
      name: 'suggestions',
      component: () => import('@/views/Suggestions.vue'),
      meta: { title: 'Optimization Suggestions' }
    },
    {
      path: '/experiments',
      name: 'experiments',
      component: () => import('@/views/Experiments.vue'),
      meta: { title: 'A/B Experiments', requiresAdmin: true }
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/Chat.vue'),
      meta: { title: 'Chat' }
    },
    {
      path: '/users',
      name: 'users',
      component: () => import('@/views/Users.vue'),
      meta: { title: 'Users', requiresAdmin: true }
    },
    {
      path: '/groups',
      name: 'groups',
      component: () => import('@/views/Groups.vue'),
      meta: { title: 'Principals', requiresAdmin: true }
    },
    {
      path: '/permissions',
      name: 'permissions',
      component: () => import('@/views/Permissions.vue'),
      meta: { title: 'Collection Permissions', requiresAdmin: true }
    },
    {
      path: '/api-docs',
      name: 'api-docs',
      component: () => import('@/views/ApiDocs.vue'),
      meta: { title: 'API Documentation' }
    },
  ],
})

router.beforeEach(createAuthGuard(router))

router.afterEach((to) => {
  document.title = `${to.meta.title || 'Admin'} - RAG in a Box`
})

export default router
