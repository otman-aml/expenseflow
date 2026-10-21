/**
 * Libelle francais de chaque statut, cle par identifiant du backend.
 *
 * <p>
 * Les cles sont volontairement <em>identiques</em> aux noms de l'enumeration
 * Java {@code ExpenseStatus}, et non des codes maison : toute evolution du
 * backend se reflete ici sans reindentation, et la comparaison directe reste
 * possible. Traduire la valeur de l'enumeration par un index fragile
 * (DRAFT = 0, SUBMITTED = 1...) aurait casse silencieusement des badges a la
 * premiere insertion d'un statut.
 * </p>
 *
 * @type {Record<string, string>}
 */
const LIBELLES = {
  DRAFT: 'Brouillon',
  SUBMITTED: 'Soumise',
  PENDING_MANAGER: 'En attente manager',
  APPROVED_MANAGER: 'Approuvée manager',
  REJECTED_MANAGER: 'Rejetée manager',
  PENDING_FINANCE: 'En attente finance',
  REIMBURSED: 'Remboursée',
  REJECTED_FINANCE: 'Rejetée finance'
}

/**
 * Classes Tailwind associees a chaque statut.
 *
 * <p>
 * <strong>Couleurs choisies pour leur signification, pas pour la visibilite :
 * </strong> un statut final et irreversible (remboursee, rejetee finance) est
 * rendu dans un ton plein, alors qu'un statut provisoire utilise un fond clair
 * et une police coloree. L'utilisateur distingue ainsi d'un coup d'oeil une
 * note definitive d'une note encore en cours de traitement.
 * </p>
 *
 * @type {Record<string, string>}
 */
const COULEURS = {
  DRAFT: 'bg-gray-100 text-gray-700',
  SUBMITTED: 'bg-blue-100 text-blue-700',
  PENDING_MANAGER: 'bg-yellow-100 text-yellow-700',
  APPROVED_MANAGER: 'bg-green-100 text-green-700',
  REJECTED_MANAGER: 'bg-red-100 text-red-700',
  PENDING_FINANCE: 'bg-yellow-100 text-yellow-700',
  REIMBURSED: 'bg-emerald-600 text-white',
  REJECTED_FINANCE: 'bg-red-600 text-white'
}

/** Style applique quand le statut est absent du mapping. */
const COULEUR_INCONNUE = 'bg-gray-100 text-gray-700'

/**
 * Pastille d'un statut de note de frais.
 *
 * @param {{ status: string }} props statut a representer
 * @returns {React.ReactNode} pastille coloree avec son libelle
 */
function StatusBadge({ status }) {
  // Repli sur le statut brut si le backend introduit une valeur inconnue :
  // mieux vaut afficher un identifiant non traduit que masquer l'information.
  const libelle = LIBELLES[status] ?? status ?? '—'
  const couleurs = COULEURS[status] ?? COULEUR_INCONNUE

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${couleurs}`}
    >
      {libelle}
    </span>
  )
}

export default StatusBadge