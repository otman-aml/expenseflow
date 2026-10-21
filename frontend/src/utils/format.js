/**
 * Helpers de formatage d'affichage.
 *
 * <p>
 * Ils sont isoles dans un module dedie plutot queinline dans les composants
 * pour une raison de coherence : un montant doit s'afficher partout de la meme
 * facon, et la regle de formatage ne doit pas dependre du composant qui
 * l'appelle.
 * </p>
 */

/**
 * Formateur de dates francais (jour/mois/annee puis heure).
 *
 * <p>
 * La locale {@code fr-MA} est fixee explicitement : sans elle, le rendu depend
 * de la langue du navigateur, et un developpeur anglais verra "9/27/2026"
 * la ou un utilisateur voit "27/09/2026".
 * </p>
 */
const FORMAT_DATE = new Intl.DateTimeFormat('fr-MA', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit'
})

/**
 * Formateur de montants en dirhams, sans symbole pour eviter la double
 * mention « 150,00 MAD MAD ».
 *
 * <p>
 * <strong>Note de lecture</strong> : la locale {@code fr-MA} utilise le point
 * comme separateur de milliers et la virgule comme separateur decimal, soit
 * « 1.234,57 MAD ». C'est une convention propre au Maroc et a la France
 * (l'espace insecable n'etant utilise qu'a partir de fr-FR dans certains
 * contextes). Les montants qui depassent le millier auront donc un rendu
 * different de celui qu'on attendrait instinctivement.
 * </p>
 */
const FORMAT_MONTANT = new Intl.NumberFormat('fr-MA', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
})

/**
 * Formate une date ISO en chaine lisible.
 *
 * @param {string} dateText date au format ISO 8601 renvoyee par le backend
 * @returns {string} date formatee, ou un tiret si la valeur est absente
 *
 * @example
 * formatDate('2026-09-27T22:38:00Z') // -> "27/09/2026 22:38"
 */
export function formatDate(dateText) {
  if (!dateText) {
    return '—'
  }

  const date = new Date(dateText)

  // Une date invalide donne un Invalid Date : on preferne le tiret a un
  // "Invalid Date" affiche a l'utilisateur.
  if (Number.isNaN(date.getTime())) {
    return '—'
  }

  return FORMAT_DATE.format(date)
}

/**
 * Formate un montant en dirhams marocains.
 *
 * @param {number|string} valeur montant ; accepte les chaines pour tolerer
 *                          un BigDecimal sérialise sans suffixe
 * @returns {string} montant formatee avec la devise
 *
 * @example
 * formatMontant(150)      // -> "150,00 MAD"
 * formatMontant('150.5')  // -> "150,50 MAD"
 */
export function formatMontant(valeur) {
  if (valeur === null || valeur === undefined || valeur === '') {
    return '—'
  }

  // Le backend renvoie un JSON number, mais Number() accepte aussi une chaine :
  // la conversion reste correcte dans les deux cas.
  const nombre = typeof valeur === 'number' ? valeur : Number(valeur)

  if (Number.isNaN(nombre)) {
    return '—'
  }

  return `${FORMAT_MONTANT.format(nombre)} MAD`
}