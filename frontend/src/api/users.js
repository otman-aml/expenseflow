import client from './client'

/**
 * Appels REST du profil de l'utilisateur connecté.
 *
 * <p>
 * Les trois routes sont en {@code /users/me} : le compte visé est
 * toujours celui du porteur du jeton, déduit côté serveur — jamais un
 * identifiant passé par le client. Un appelant n'a ainsi aucun moyen de
 * lire ou modifier le profil d'autrui.
 * </p>
 */

/**
 * Profil courant (nom, e-mail, rôle).
 *
 * @returns {Promise<{id: number, nom: string, email: string, role: string}>}
 * @throws {import('axios').AxiosError} 401 si le jeton est absent ou expiré
 */
export async function getMyProfile() {
  const { data } = await client.get('/users/me')
  return data
}

/**
 * Met à jour le nom et l'e-mail du compte courant.
 *
 * <p>
 * La réponse du serveur (profil enregistré) est renvoyée telle quelle :
 * c'est elle qui doit remplacer l'objet {@code user} du store Zustand,
 * afin que le header affiche immédiatement les nouvelles valeurs. Aucune
 * reconstruction locale, qui serait une seconde source de vérité.
 * </p>
 *
 * @param {{ nom: string, email: string }} payload nouveau nom et nouvel e-mail
 * @returns {Promise<{id: number, nom: string, email: string, role: string}>} profil enregistré
 * @throws {import('axios').AxiosError} 409 si l'e-mail est déjà pris,
 *          400 si le corps est invalide
 */
export async function updateMyProfile(payload) {
  const { data } = await client.put('/users/me', payload)
  return data
}

/**
 * Change le mot de passe du compte courant.
 *
 * <p>
 * <strong>Un ancien mot de passe erroné renvoie 400, jamais 401</strong> :
 * l'intercepteur du client (qui ne déconnecte que sur 401) laisse donc la
 * session ouverte, et l'utilisateur reste sur la page pour corriger sa
 * saisie.
 * </p>
 *
 * @param {{ ancienMotDePasse: string, nouveauMotDePasse: string, confirmationMotDePasse: string }} payload
 * @returns {Promise<void>} résolu une fois le mot de passe changé (réponse 204 sans corps)
 * @throws {import('axios').AxiosError} 400 ancien incorrect, confirmation non concordante ou validation
 */
export async function changeMyPassword(payload) {
  await client.put('/users/me/password', payload)
}