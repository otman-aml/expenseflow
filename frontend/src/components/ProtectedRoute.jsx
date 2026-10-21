import { useLocation, Navigate } from 'react-router-dom'

import { useAuthStore } from '../stores/authStore'

/**
 * Garde de route : n'affiche ses enfants qu'a un utilisateur connecte.
 *
 * <p>
 * <strong>Ce que ce composant protege, et ce qu'il ne protege pas.</strong> Il
 * protege l'<em>affichage</em> des pages privees. Il ne constitue en aucun cas
 * une barriere de securite : un utilisateur non authentifie peut toujours
 * appeler directement l'API. La seule protection reelle est celle du backend,
 * portee par {@code @PreAuthorize} et le filtre JWT, et verifiee par les tests
 * d'integration de la section 1.9. Ce composant n'est qu'un gain
 * d'experience utilisateur : il evite d'afficher une page vide avant que la
 * requete ne revienne en 401.
 * </p>
 *
 * <p>
 * <strong>Lecture selective du store</strong> : on ne selectionne que
 * {@code token}, puis on en derive un booleen. Deux raisons.
 * </p>
 *
 * <ul>
 *   <li>Selectionner {@code isAuthenticated} directement ne fonctionnerait pas :
 *       c'est une <em>fonction</em>, et toute fonction est truthy en
 *       JavaScript. La garde ne redirigerait donc jamais et laisserait passer
 *       un utilisateur non connecte vers la page protegee ;</li>
 *   <li>Deriver le booleen ici evite en outre de re-rendre le composant a
 *       chaque modification du store : seule la valeur de {@code token}
 *       compte, pas {@code loading} ni {@code user}.</li>
 * </ul>
 *
 * @param {{ children: React.ReactNode }} props contenu a afficher si connecte
 * @returns {React.ReactNode} les enfants, ou une redirection vers /login
 */
function ProtectedRoute({ children }) {
  const token = useAuthStore((state) => state.token)
  const location = useLocation()

  if (!token) {
    // 'replace' : la page cible ne doit pas rester dans l'historique.
    // Sinon, apres connexion, le bouton "retour" renverrait vers cette meme
    // garde, qui redirigerait a nouveau vers /login, bouclant le navigateur.
    // `state.from` : la page demandee — LoginPage y retourne apres succes.
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return children
}

export default ProtectedRoute