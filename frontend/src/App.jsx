import { useEffect, useRef } from 'react'
import { Navigate, Outlet, Route, Routes, useNavigate } from 'react-router-dom'

import { setOnUnauthorized } from './api/client'
import AppLayout from './components/AppLayout'
import ProtectedRoute from './components/ProtectedRoute'
import RoleRoute from './components/RoleRoute'
import DashboardPage from './pages/DashboardPage'
import EditNotePage from './pages/EditNotePage'
import ForgotPasswordPage from './pages/ForgotPasswordPage'
import LoginPage from './pages/LoginPage'
import NoteDetailPage from './pages/NoteDetailPage'
import NotesListPage from './pages/NotesListPage'
import NewNotePage from './pages/NewNotePage'
import ProfilePage from './pages/ProfilePage'
import ManagerValidationsPage from './pages/manager/ValidationsPage'
import FinanceValidationsPage from './pages/finance/ValidationsPage'
import UsersPage from './pages/admin/UsersPage'
import { useAuthStore } from './stores/authStore'

/**
 * Enveloppe commune a toutes les pages privees : garde d'authentification puis
 * ossature.
 *
 * <p>
 * Utilisee comme route mere via {@code <Outlet />}, elle evite de repeter
 * {@code <ProtectedRoute><AppLayout>} sur chaque route. Ajouter une page
 * privee revient desormais a ecrire une seule ligne, sans risquer d'en
 * oublier la protection.
 * </p>
 *
 * @returns {React.ReactNode} ossature de l'application
 */
function PrivateRoute() {
  return (
    <ProtectedRoute>
      <AppLayout>
        <Outlet />
      </AppLayout>
    </ProtectedRoute>
  )
}

/**
 * Table de routage et branchement du callback 401.
 *
 * <p>
 * <strong>Pourquoi le callback est enregistre ici</strong> et non dans
 * {@code client.js} : la redirection exige {@code useNavigate}, un hook React
 * qui n'a de sens que dans un composant. Le module Axios vit en dehors de React :
 * il expose donc un point d'enregistrement ({@code setOnUnauthorized}) qu'un
 * composant vient brancher.
 * </p>
 *
 * <p>
 * <strong>dependances du useEffect</strong> : React rejoue un effet a chaque
 * changement de l'une de ses dependances. Referencer directement {@code logout}
 * et {@code navigate} ici les ferait changer a chaque rendu, et l'enregistrement
 * serait refait sans arret. {@code logout} est donc lu dans
 * {@code useAuthStore.getState()} et {@code navigate} dans une ref : l'effet ne
 * s'execute qu'une seule fois, au montage.
 * </p>
 */
function App() {
  const navigate = useNavigate()
  const navigateRef = useRef(navigate)

  useEffect(() => {
    navigateRef.current = navigate
  }, [navigate])

  useEffect(() => {
    setOnUnauthorized(() => {
      // getState() plutot que la valeur capturee : garantit qu'on appelle
      // l'action la plus recente du store, meme si elle change un jour.
      useAuthStore.getState().logout()
      navigateRef.current('/login', { replace: true })
    })

    // Nettoyage au demontage : evite qu'un callback referencant un composant
    // demonte ne subsiste et ne tente une navigation dans le vide.
    return () => setOnUnauthorized(null)
  }, [])

  return (
    <Routes>
      {/* Routes publiques */}
      <Route path="/login" element={<LoginPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />

      {/* Routes privees : chacune herite de la garde et de l'ossature.
          '/notes/new' doit preceder '/notes/:id' : le routeur matche dans
          l'ordre, et '/notes/new' serait sinon capture par ':id' avec
          id="new". */}
      <Route element={<PrivateRoute />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/notes" element={<NotesListPage />} />
        <Route path="/notes/new" element={<NewNotePage />} />
        {/* '/notes/:id/edit' DOIT preceder '/notes/:id' : le routeur matche
            dans l'ordre, et le second absorberait le premier en lisant
            '16/edit' comme un identifiant. */}
        <Route path="/notes/:id/edit" element={<EditNotePage />} />
        <Route path="/notes/:id" element={<NoteDetailPage />} />
        <Route path="/profile" element={<ProfilePage />} />

        {/* File de validation manager. La finance y figure aussi : elle
            dispose du meme droit d'approuver, decide en section 2.1. La garde
            ne fait qu'eviter l'affichage ; le 403 du serveur fait autorite. */}
        <Route
          path="/manager/validations"
          element={
            <RoleRoute roles={['MANAGER', 'FINANCE_ADMIN']}>
              <ManagerValidationsPage />
            </RoleRoute>
          }
        />

        {/* File de remboursement : finance seule. Le MANAGER en est exclu
            malgre qu'il traite des notes dans cet etat, car la decision
            2.5-bis ne lui accorde pas de lecture sur cette file. */}
        <Route
          path="/finance/validations"
          element={
            <RoleRoute roles={['FINANCE_ADMIN']}>
              <FinanceValidationsPage />
            </RoleRoute>
          }
        />

        {/* Administration des comptes : ADMIN seul. Le 403 du serveur fait
            autorite — cette garde ne sert qu'a eviter d'afficher une page
            vide aux autres roles. */}
        <Route
          path="/admin/users"
          element={
            <RoleRoute roles={['ADMIN']}>
              <UsersPage />
            </RoleRoute>
          }
        />
      </Route>

      {/* Toute URL inconnue ramene a l'accueil, qui appliquera elle-meme la
          protection. Le 'replace' evite de laisser l'URL fautive dans
          l'historique : sans cela, le bouton "retour" renverrait
          indefiniment vers une page qui redirige encore. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App