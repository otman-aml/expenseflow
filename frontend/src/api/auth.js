import client from './client'

/**
 * Appels REST du domaine authentification.
 *
 * <p>
 * Ce module ne contient que la traduction HTTP : URL, methode, corps. Toute la
 * logique d'etat (stocker le jeton, ouvrir une session) appartient au store
 * Zustand, et le traitement des erreurs a l'affichage incombe aux composants,
 * via {@link getErrorMessage}.
 * </p>
 *
 * <p>
 * Les URL sont ecrites <strong>sans</strong> le prefixe {@code /api} : il est
 * deja porte par le {@code baseURL} de l'instance Axios.
 * </p>
 */

/**
 * Connexion avec une adresse e-mail et un mot de passe.
 *
 * @param {string} email        adresse e-mail de l'utilisateur
 * @param {string} motDePasse   mot de passe en clair
 * @returns {Promise<import('../stores/authStore').AuthResponse>} reponse du backend
 * @throws {import('axios').AxiosError} en cas d'identifiants invalides (401)
 *
 * @example
 * const { token, user } = await login('alice@expenseflow.ma', 'MotDePasse1!')
 */
export async function login(email, motDePasse) {
  console.log('[ExpenseFlow] API login:', email) // TODO: retirer avant toute mise en production
  const { data } = await client.post('/auth/login', { email, motDePasse })
  return data
}

/**
 * Inscription d'un nouvel utilisateur.
 *
 * <p>
 * Le role n'est pas transmis : le backend le force a {@code EMPLOYEE} et
 * refuserait un role dans le corps, ce qui empeche une escalade de privileges
 * depuis le client.
 * </p>
 *
 * @param {string} nom          nom complet
 * @param {string} email        adresse e-mail
 * @param {string} motDePasse   mot de passe (8 a 100 caracteres)
 * @returns {Promise<import('../stores/authStore').AuthResponse>} jeton et profil
 * @throws {import('axios').AxiosError} si l'e-mail est deja pris (409) ou invalide (400)
 */
export async function register(nom, email, motDePasse) {
  const { data } = await client.post('/auth/register', { nom, email, motDePasse })
  return data
}

/**
 * Profil de l'utilisateur correspondant au jeton present dans l'intercepteur.
 *
 * <p>
 * Sert a verifier qu'un jeton est encore accepte par le serveur, typiquement
 * au demarrage de l'application. Dans le flux de connexion ordinaire, ce n'est
 * pas necessaire : {@code AuthResponse} contient deja {@code user}.
 * </p>
 *
 * @returns {Promise<import('../stores/authStore').UserResponse>} profil courant
 * @throws {import('axios').AxiosError} 401 si le jeton est absent, expire ou invalide
 */
export async function me() {
  const { data } = await client.get('/auth/me')
  return data
}