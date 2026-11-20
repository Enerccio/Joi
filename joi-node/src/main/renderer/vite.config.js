import {defineConfig} from 'vite';

// built into dist/, which Maven puts on the node's classpath as joi-renderer/
export default defineConfig({
  base: './',
  build: {outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 2000}
});
