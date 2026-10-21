import axios from 'axios'

import { useAuthStore } from '../stores/authStore'

/**
 * Instance Axios unique partagee par toute l'application.
 *
 * <p>
 * <strong>L'URL de l'API est lue a la construction</strong>, via
 * {@code VITE_API_URL}. Deux cas, volontairement distincts :
 * </p>
 * <ul>
 * <li><strong>Variable definie</strong> (production, front et API sur deux
 * domaines) : les requetes partent en absolu, et le navigateur effectue un
 * preflight CORS. C'est le seul schema qui fonctionne quand les deux
 * applications ne partagent pas une origine.</li>
 * <li><strong>Variable absente</strong> (developpement) : le chemin reste
 * relatif, et le proxy Vite relaie vers le backend local. Le navigateur ne
 * voit jamais deux origines, donc aucun en-tete CORS n'est echange en
 * developpement.</li>
 * </ul>
 *
 * <p>
 * <strong>Pourquoi ne pas toujours utiliser une URL absolue.</strong> Parce
 * qu'alors le developpement local devrait lui-meme dependre du CORS, pour
 * tester en local exactement ce qui casse en production. Le repli conserve un
 * developpement sans CORS, tout en autorisant un deploiement sur deux
 * domaines.
 * </p>
 *
 * <p>
 * <strong>Le prefixe {@code VITE_} est obligatoire</strong> : Vite n'injecte
 * dans le bundle que les variables qui le portent. Une variable nommee
 * {@code API_URL} disparaitrait a la compilation, et le front appellerait le
 * domaine Vercel lui-meme, qui n'expose aucune API — sans le moindre message
 * d'erreur.
 * </p>
 *
 * <p>
 * <strong>Token en memoire uniquement</strong> : il n'est jamais ecrit dans
 * {@code localStorage} ni {@code sessionStorage}. Un refresh de page provoque
 * donc une deconnexion. C'est un choix assume pour ce MVP : un jeton en
 * memoire est plus resistant au vol par XSS qu'un jeton persistant, que
 * n'importe quel script injecte peut relire. Le prix a payer est qu'aucun
 * onglet ne peut pre-authentifier un autre.
 * </p>
 */
const client = axios.create({
  baseURL: import.meta.env.VITE_API_URL
    ? `${import.meta.env.VITE_API_URL}/api`
    : '/api',
  // 10 s suffisent pour une API locale ou un service awakenable. Au-dela, on
  // prefererait une annulation explicite cote composant plutot qu'un
  // delai d'attente muet dans lequel l'utilisateur ne comprend rien.
  timeout: 10000
})

// Route de connexion : elle renvoie legitimement un 401 quand les identifiants
// sont faux. La voir declencher la deconnexion automatique provoquerait une
// boucle : 401 -> logout -> remount du formulaire -> 401 -> ...
const LOGIN_PATH = '/auth/login'

/**
 * Callback invoque lors d'un 401 sur une requete authentifiee.
 *
 * <p>
 * L'intercepteur vit dans un module JavaScript ordinaire : il ne peut pas
 * appeler {@code useNavigate()}, qui est un hook React reserve aux composants.
 * On expose donc un point d'enregistrement que {@code App.jsx} remplit une fois
 * montee dans le routeur.
 * </p>
 *
 * @type {(() => void) | null}
 */
let onUnauthorized = null

/**
 * Enregistre le callback appele sur reponse 401.
 *
 * <p>
 * A appeler depuis un {@code useEffect} de {@code App.jsx} : le composant doit
 * etre rendu <em>a l'interieur</em> du routeur pour que la navigation soit
 * possible.
 * </p>
 *
 * @param {() => void} callback
 */
export function setOnUnauthorized(callback) {
  onUnauthorized = callback
}

// Interception avant envoi : ajoute le jeton si l'utilisateur est connecte.
client.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

// Interception apres reponse : deconnecte sur 401 non lie a la connexion.
client.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    // FIXME: un 401 (y compris un simple token expiré) déconnecte l'utilisateur
    //        sans prévenir, y compris au milieu d'une saisie — pas de refresh
    //        token pour prolonger la session en douceur.
    console.log('[ExpenseFlow] API error:', status, error.config?.url)
    // On teste le chemin de l'URL et non l'URL entiere : elle peut etre
    // relative ('/auth/login') ou absolue ('http://localhost:4300/api/auth/
    // login') selon la facon dont l'appel a ete ecrit.
    const url = error.config?.url ?? ''

    if (status === 401 && !url.includes(LOGIN_PATH)) {
      // getState() et non le hook : un intercepteur s'execute hors du cycle de
      // rendu React, ou aucun hook ne peut etre appele.
      useAuthStore.getState().logout()
      onUnauthorized?.()
    }

    // L'erreur est relansee telle quelle : chaque appelant decide de la
    // gerer et le message est extrait via getErrorMessage().
    return Promise.reject(error)
  }
)

export default client