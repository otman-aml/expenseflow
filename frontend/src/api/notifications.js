import client from './client'

/**
 * Appels REST des notifications de l'utilisateur connecté.
 *
 * <p>
 * Comme le profil ({@code /users/me}), toutes les routes reposent sur le
 * porteur du jeton : aucun identifiant d'utilisateur n'est jamais transmis,
 * le client n'a donc aucun moyen de lire — ni de marquer comme lue — la
 * notification d'autrui.
 * </p>
 */

/**
 * Notifications non lues de l'utilisateur courant, des plus récentes aux
 * plus anciennes.
 *
 * @returns {Promise<Array<{id: number, type: string, message: string, lien: string|null, lue: boolean, createdAt: string}>>}
 * @throws {import('axios').AxiosError} 401 si le jeton est absent ou expiré
 */
export async function getUnreadNotifications() {
  const { data } = await client.get('/notifications')
  return data
}

/**
 * Marque une notification comme lue.
 *
 * <p>
 * La réponse 204 est sans corps : le front met à jour sa copie locale
 * (retrait de la pastille) sans second appel de rafraîchissement.
 * </p>
 *
 * @param {number} id identifiant de la notification
 * @returns {Promise<void>} résolu une fois le marquage enregistré
 * @throws {import('axios').AxiosError} 404 si la notification n'existe pas
 *          ou appartient à un autre utilisateur
 */
export async function markNotificationAsRead(id) {
  await client.put(`/notifications/${id}/lue`)
}

/**
 * Marque toutes les notifications de l'utilisateur courant comme lues.
 *
 * @returns {Promise<void>} résolu une fois le marquage enregistré
 * @throws {import('axios').AxiosError} 401 si le jeton est absent ou expiré
 */
export async function markAllNotificationsAsRead() {
  await client.put('/notifications/toutes-lues')
}