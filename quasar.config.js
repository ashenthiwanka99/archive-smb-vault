// Quasar config — Night Archive (private photo vault)
// https://v2.quasar.dev/quasar-cli-vite/quasar-config-file

export default function (/* ctx */) {
  return {
    boot: [
      'pinia',
      'smb-plugin'
    ],

    css: [
      'app.scss'
    ],

    extras: [
      'material-icons',
      'mdi-v7'
    ],

    build: {
      target: {
        browser: ['es2022', 'firefox115', 'chrome115', 'safari14'],
        node: 'node20'
      },
      vueRouterMode: 'hash'
    },

    devServer: {
      open: false
    },

    framework: {
      config: {
        dark: true,
        brand: {
          primary: '#E8A33D',      // marigold
          secondary: '#6FA8DC',    // slate
          accent: '#E8A33D',
          dark: '#0B0D13',
          'dark-page': '#0B0D13',
          positive: '#7EA577',     // moss
          negative: '#C85A3C',     // rust
          info: '#6FA8DC',
          warning: '#E8A33D'
        },
        notify: {
          position: 'bottom',
          timeout: 2500,
          classes: 'notify-lift'
        }
      },
      plugins: [
        'Notify',
        'Dialog',
        'BottomSheet',
        'Loading',
        'LocalStorage',
        'SessionStorage'
      ]
    },

    animations: 'all',

    capacitor: {
      hideSplashscreen: false,
      appId: 'com.mrdon.server',
      appName: 'Server',
      version: '1.0.0'
    }
  }
}
