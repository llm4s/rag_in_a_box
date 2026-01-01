declare module 'swagger-ui-dist/swagger-ui-es-bundle.js' {
  interface SwaggerUIOptions {
    domNode: HTMLElement
    url?: string
    spec?: object
    deepLinking?: boolean
    presets?: unknown[]
    plugins?: unknown[]
    layout?: string
    defaultModelsExpandDepth?: number
    defaultModelExpandDepth?: number
    docExpansion?: 'list' | 'full' | 'none'
    filter?: boolean | string
    showExtensions?: boolean
    showCommonExtensions?: boolean
  }

  interface SwaggerUIBundle {
    (options: SwaggerUIOptions): void
    presets: {
      apis: unknown
    }
    SwaggerUIStandalonePreset: unknown
  }

  const SwaggerUI: SwaggerUIBundle
  export default SwaggerUI
}
