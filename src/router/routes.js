export default [
  {
    path: '/',
    component: () => import('layouts/MainLayout.vue'),
    children: [
      { path: '',        redirect: '/files' },
      { path: 'files',    name: 'files',    component: () => import('pages/FileBrowserPage.vue') },
      { path: 'recents',  name: 'recents',  component: () => import('pages/RecentsPage.vue') },
      { path: 'gallery',  name: 'gallery',  component: () => import('pages/GalleryBackupPage.vue') },
      { path: 'settings', name: 'settings', component: () => import('pages/SettingsPage.vue') },
      { path: 'gallery-settings', redirect: '/gallery' },
      { path: 'share-in', name: 'share-in', component: () => import('pages/ShareReceiverPage.vue') },
      { path: 'info',     name: 'info',     component: () => import('pages/NetworkInfoPage.vue') },
      { path: 'starred',  name: 'starred',  component: () => import('pages/StarredPage.vue') },
      { path: 'downloads', name: 'downloads', component: () => import('pages/DownloadsPage.vue') }
    ]
  },
  {
    path: '/preview',
    component: () => import('layouts/BlankLayout.vue'),
    children: [
      { path: '', name: 'preview', component: () => import('pages/FilePreviewPage.vue') }
    ]
  },
  {
    path: '/search',
    component: () => import('layouts/BlankLayout.vue'),
    children: [
      { path: '', name: 'search', component: () => import('pages/SearchPage.vue') }
    ]
  },
  {
    path: '/duplicates',
    component: () => import('layouts/BlankLayout.vue'),
    children: [
      { path: '', name: 'duplicates', component: () => import('pages/DuplicatesPage.vue') }
    ]
  },
  {
    path: '/cleanup',
    component: () => import('layouts/BlankLayout.vue'),
    children: [
      { path: '', name: 'cleanup', component: () => import('pages/CleanupQueuePage.vue') }
    ]
  },
  {
    path: '/storage',
    component: () => import('layouts/BlankLayout.vue'),
    children: [
      { path: '', name: 'storage', component: () => import('pages/StoragePage.vue') }
    ]
  },
  {
    path: '/activity',
    component: () => import('layouts/BlankLayout.vue'),
    children: [
      { path: '', name: 'activity', component: () => import('pages/ActivityPage.vue') }
    ]
  },
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/FileBrowserPage.vue')
  }
]
