/**
 * Extraction d'un message d'erreur exploitable par l'utilisateur.
 *
 * <p>
 * Le backend renvoie plusieurs formes d'erreur, et chacune a son propre champ
 * utile. Un acces generique sur un seul champ echouerait donc sur les autres :
 * </p>
 *
 * <ul>
 *   <li>{@code {"error":"Unauthorized","message":"Authentication required"}}
 *       et {@code {"error":"Forbidden","message":"Access denied"}} : le champ
 *       {@code message} est le plus parlant ;</li>
 *   <li>{@code {"error":"Validation failed","fields":{"email":"..."}} : il
 *       n'y a <strong>aucun</strong> champ {@code message}. Le detail utile
 *       est dans {@code fields}, objet dont les cles sont les champs du
 *       formulaire ;</li>
 *   <li>{@code {"error":"An unexpected error occurred"}} : 500 renvoye par le
 *       {@code GlobalExceptionHandler}, seul {@code error} est disponible.</li>
 * </ul>
 *
 * <p>
 * L'ordre de preference est donc {@code message}, puis {@code fields} aplati,
 * puis {@code error}, et enfin un texte generique.
 * </p>
 */

/**
 * Construit le message d'une erreur de validation renvoyee par le backend.
 *
 * @param {Record<string, string>} fields paires champ / message
 * @returns {string} message unique, ou une chaine vide si objet vide
 */
function formaterChamps(fields) {
  if (!fields || typeof fields !== 'object') {
    return ''
  }

  const details = Object.entries(fields).map(([champ, texte]) => `${champ} : ${texte}`)

  return details.length > 0 ? details.join(' — ') : ''
}

/**
 * Extrait un message lisible depuis une erreur Axios.
 *
 * @param {unknown} error erreur levee par l'appel HTTP, ou valeur quelconque
 * @returns {string} message affichable, jamais vide
 *
 * @example
 * try {
 *   await login(email, motDePasse)
 * } catch (error) {
 *   setErreur(getErrorMessage(error))
 * }
 */
export function getErrorMessage(error) {
  // Absence de reponse : la requete n'a pas atteint le serveur (reseau coupe,
  // backend arrete, delai depasse). Distinguer ce cas evite d'afficher « Bad
  // credentials » a l'utilisateur alors que son mot de passe est correct.
  if (error?.response === undefined) {
    return error?.code === 'ECONNABORTED'
      ? "Le serveur ne repond pas. Verifiez qu'il est demarre."
      : 'Impossible de joindre le serveur.'
  }

  const data = error.response.data
  const status = error.response.status

  // Cas validation : pas de "message", mais un objet "fields".
  const champs = formaterChamps(data?.fields)
  if (champs) {
    return champs
  }

  const message = data?.message || data?.error || ''

  // 403 : le corps serveur se limite à « Access denied » (texte technique,
  // identique pour l'auto-suppression d'un admin et pour un profil sans le
  // rôle ADMIN). Sans traduction, l'utilisateur ne sait ni ce qui est refusé
  // ni quelle suite donner — le repli nomme l'action et le correctif.
  if (status === 403 && (!message || message === 'Access denied')) {
    return 'Accès refusé : votre profil ne permet pas cette action. Reconnectez-vous si votre rôle a changé.'
  }

  // Les 409 (compte avec notes, dernier administrateur, e-mail pris) portent
  // déjà un message métier explicite : ils sont servis tels quels.
  return message || 'Une erreur est survenue'
}