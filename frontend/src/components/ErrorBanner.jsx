/**
 * Bandeau d'erreur destine a l'utilisateur.
 *
 * <p>
 * Centralise dans un composant la mise en forme, jusqu'ici dupliquee sur
 * quatre pages. Toute divergence de style entre deux messages d'erreur
 * disparait, et l'accessibilite est traitee une seule fois ({@code role} et
 * {@code aria-live} permettent aux lecteurs d'ecran d'annoncer le message
 * des son insertion).
 * </p>
 *
 * @param {{ message: string }} props texte a afficher
 * @returns {React.ReactNode} bandeau d'erreur
 */
function ErrorBanner({ message }) {
  return (
    <div
      role="alert"
      className="mt-6 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      {message}
    </div>
  )
}

export default ErrorBanner