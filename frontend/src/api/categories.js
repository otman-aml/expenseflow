import client from './client'

/**
 * Appels REST du catalogue des categories de depense.
 *
 * <p>
 * La lecture est ouverte a tout utilisateur authentifie
 * ({@code @PreAuthorize("isAuthenticated()")} cote backend) : les categories
 * servent a renseigner le formulaire de note de frais, et un EMPLOYEE doit
 * pouvoir les lister. L'<em>ecriture</em> reste reservee au FINANCE_ADMIN.
 * </p>
 */

/**
 * Liste toutes les categories.
 *
 * @returns {Promise<import('./categories').CategoryResponse[]>} categories disponibles
 * @throws {import('axios').AxiosError} 401 si le jeton est absent ou expire
 */
export async function listCategories() {
  const { data } = await client.get('/categories')
  return data
}

/**
 * Recupere une categorie par son identifiant.
 *
 * @param {number|string} id identifiant de la categorie
 * @returns {Promise<import('./categories').CategoryResponse>} categorie
 * @throws {import('axios').AxiosError} 404 categorie inconnue
 */
export async function getCategoryById(id) {
  const { data } = await client.get(`/categories/${id}`)
  return data
}