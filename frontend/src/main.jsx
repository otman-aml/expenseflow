import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

import App from './App.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    {/* BrowserRouter doit envelopper App : les composants internes (App,
        ProtectedRoute, LoginPage) utilisent useNavigate / Navigate, qui ne
        fonctionnent qu'a l'interieur d'un contexte de routage.

        On utilise BrowserRouter et non HashRouter pour avoir des URL propres
        (/login plutot que /#/login). Cela impose au serveur de renvoyer
        index.html sur toute route inconnue : cote backend, la configuration
        Spring Security devra autoriser ces chemins en mode "forward", point
        a traiter lors du deploiement. */}
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>
)