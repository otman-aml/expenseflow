import client from './client'

/**
 * Appels REST du domaine « brique IA ».
 *
 * <p>
 * Meme principe que les autres modules d'API : ce fichier ne traduit que
 * l'URL et la methode. Le traitement des erreurs, lui, incombe aux
 * composants via {@link getErrorMessage} — sauf ici, ou les codes portent une
 * information qu'un message unique ne saurait restituer.
 * </p>
 */

/**
 * Extrait une depense depuis un texte en langage naturel.
 *
 * <p>
 * <strong>Les codes d'erreur sont discrimines ici</strong>, contrairement aux
 * autres appels du projet qui laissent le composant traiter un message unique.
 * La raison est concrete : le backend renvoie des messages de nature très
 * différente selon le code.
 * </p>
 *
 * <ul>
 *   <li><strong>503</strong> : aucune cle API configuree. L'utilisateur ne peut
 *       rien corriger, et la saisie manuelle reste le chemin ;
 *       <strong>422</strong> : la reponse du modele a ete refusee a la
 *       validation. Le message est actionnable — « Montant non determine »,
 *       « Categorie inconnue : 99 » — et doit donc etre affiche tel quel ;
 *       <strong>502 / 504</strong> : panne ou delai chez le fournisseur. Aucun
 *       detail n'est utile, l'utilisateur doit reessayer plus tard.</li>
 * </ul>
 *
 * @param {string} texte description libre de la depense
 * @returns {Promise<{ ligne: { categorieId: number, montant: number, dateDepense: string, description: string|null } }>}
 *          ligne pre-remplie
 * @throws {import('axios').AxiosError} 422 extraction impossible, 503 brique
 *         non configuree, 502/504 service indisponible
 */
export async function extractExpense(texte) {
  try {
    const { data } = await client.post('/ai/extract-expense', { texte })
    return data
  } catch (error) {
    throw new Error(messageAdapté(error))
  }
}

/**
 * Traduit une erreur de l'extraction en message affichable.
 *
 * @param {unknown} erreur erreur Axios levee
 * @returns {string} message affichable
 */
function messageAdapté(erreur) {
  const statut = erreur?.response?.status

  if (statut === 503) {
    return "Le service d'extraction IA n'est pas configure. Saisissez la depense a la main."
  }

  if (statut === 504) {
    return "Le service d'extraction a mis trop de temps a repondre. Reessayez ou saisissez a la main."
  }

  if (statut === 502) {
    return "Le service d'extraction est momentanement indisponible. Reessayez plus tard."
  }

  // Le 422 porte un message actionnable, construit par le backend pour nommer
  // le champ fautif : l'afficher tel quel vaut mieux qu'un texte generique.
  const messageBackend = erreur?.response?.data?.message
  if (messageBackend) {
    return messageBackend
  }

  return "L'extraction n'a pas abouti. Saisissez la depense a la main."
}