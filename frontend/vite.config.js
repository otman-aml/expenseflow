import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vitejs.dev/config/
export default defineConfig({
  // Tailwind v4 s'installe comme plugin Vite : pas de postcss.config.js,
  // ni de tailwind.config.js obligatoire (voir src/index.css).
  plugins: [react(), tailwindcss()],
  server: {
    port: 4300,
    host: true,
    // Proxy vers le backend Spring Boot (server.port=8090).
    //
    // Pourquoi un proxy plutot qu'un appel direct a http://localhost:8090 ?
    // Le navigateur considere toute requete vers une autre origine comme
    // cross-origin et applique la politique CORS du backend, qui n'autorise
    // que http://localhost:4300. En passant par le proxy, le navigateur ne
    // voit qu'une seule origine (4300) : aucune requete preflight, aucun
    // en-tete CORS, et les cookies eventuels fonctionnent sans
    // SameSite=None. Le proxy est donc la seule facon simple de s'affranchir
    // des contraintes CORS en developpement.
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  }
})