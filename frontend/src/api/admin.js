import client from './client'

/**
 * Appels REST de l'administration des comptes.
 *
 * <p>
 * Les routes vivent sous {@code /admin/**} et exigent le rôle
 * ADMIN côté serveur ({@code @PreAuthorize}) : un appel depuis un autre
 * rôle reçoit un 403, sans effet de bord. Le front ne fait que refléter
 * cette règle — il n'en est jamais l'auteur.
 * </p>
 */

/**
 * Liste tous les comptes, avec leur manager résolu.
 *
 * @returns {Promise<Array<{id: number, nom: string, email: string, role: string, managerId: number|null, managerNom: string|null}>>}
 * @throws {import('axios').AxiosError} 401 sans jeton, 403 si le rôle n'est pas ADMIN
 */
export async function listUsers() {
  const { data } = await client.get('/admin/users')
  return data
}

/**
 * Liste les comptes éligibles au rôle de manager (sélecteur du formulaire).
 *
 * @returns {Promise<Array<{id: number, nom: string, email: string, role: string}>>}
 * @throws {import('axios').AxiosError} 401 ou 403
 */
export async function listManagers() {
  const { data } = await client.get('/admin/users/managers')
  return data
}

/**
 * Crée un compte.
 *
 * @param {{ nom: string, email: string, motDePasse: string, role: string, managerId: number|null }} payload
 * @returns {Promise<object>} le compte créé
 * @throws {import('axios').AxiosError} 409 e-mail pris, 404 manager invalide, 400 validation
 */
export async function createUser(payload) {
  const { data } = await client.post('/admin/users', payload)
  return data
}

/**
 * Modifie nom, e-mail, rôle et manager d'un compte.
 *
 * @param {number} id identifiant du compte
 * @param {{ nom: string, email: string, role: string, managerId: number|null }} payload
 * @returns {Promise<object>} le compte modifié
 * @throws {import('axios').AxiosError} 404 compte absent, 409 e-mail pris, 400 validation
 */
export async function updateUser(id, payload) {
  const { data } = await client.put(`/admin/users/${id}`, payload)
  return data
}

/**
 * Supprime un compte sans note.
 *
 * @param {number} id identifiant du compte
 * @returns {Promise<void>} résolu une fois supprimé (204 sans corps)
 * @throws {import('axios').AxiosError} 404 absent, 409 compte avec notes,
 *          403 si l'admin tente de se supprimer lui-même
 */
export async function deleteUser(id) {
  await client.delete(`/admin/users/${id}`)
}

/**
 * Statistiques d'entreprise pour le tableau de bord ADMIN.
 *
 * <p>
 * {@code GET /api/admin/stats} — totaux (comptes, notes), repartition
 * par role, appels IA des 30 derniers jours et cinq dernieres actions
 * d'administration des comptes.
 * </p>
 *
 * @returns {Promise<{totalUsers: number, usersByRole: Record<string, number>, totalExpenses: number, totalAiCalls: number, recentActions: Array<{date: string, action: string, cible: string|null>}>}>
 * @throws {import('axios').AxiosError} 401 sans jeton, 403 si le rôle n'est pas ADMIN
 */
export async function getStats() {
  const { data } = await client.get('/admin/stats')
  return data
}