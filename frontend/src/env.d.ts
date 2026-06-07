/// <reference types="vite/client" />
//声明.vue是可import文件
declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<{}, {}, any>
  export default component
}
