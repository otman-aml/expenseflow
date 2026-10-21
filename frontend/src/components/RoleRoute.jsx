import { Navigate } from 'react-router-dom'

import { useAuthStore } from '../stores/authStore'

/**
 * Garde de route reservee a certains roles.
 *
 * <p>
 * <strong>Elle ne protege rien.</strong> Comme {@link ProtectedRoute}, ce
 * composant ne fait qu'éviter d'afficher une page vide à un utilisateur qui de
 * toute façon n'en a pas le droit : le serveur répond 403 à la requête sous-
 * jacente, et c'est lui qui fait autorité. La garde ici sert à la navigation
 * d'un écran vide, pas de sécurité.
 * </p>
 *
 * <p>
 * <strong>Redirection silencieuse plutôt qu'un message d'erreur</strong>, à la
 * différence d'un accès refusé par l'API. Un utilisateur qui suit un lien de
 * validations en tant qu'employé n'a pas besoin de comprendre pourquoi : il
 * n'a simplement rien à y faire, et l'accueil lui montre ce qu'il peut faire.
 * </p>
 *
 * <p>
 * <strong>Les rôles sont additive, pas exclusif.</strong> La finance peut
 * approuver au niveau manager, donc elle accède à la file manager au même
 * titre qu'un manager. Un test booléen « un seul rôle suffit » aurait produit
 * l'exact contraire de l'intention.
 * </p>
 *
 * @param {{ roles: string[], children: React.ReactNode }} props rôles autorisés et contenu
 * @returns {React.ReactNode} les enfants, ou une redirection vers l'accueil
 */
function RoleRoute({ roles, children }) {
  const user = useAuthStore((state) => state.user)

  if (!roles.includes(user?.role)) {
    return <Navigate to="/" replace />
  }

  return children
}

export default RoleRoute