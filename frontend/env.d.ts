/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module 'sockjs-client/dist/sockjs.min.js' {
  const SockJS: any
  export default SockJS
}

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string
  readonly VITE_WS_URL?: string
  readonly VITE_USE_SOCKJS?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
