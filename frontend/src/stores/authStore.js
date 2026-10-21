import { create } from 'zustand'

import * as authApi from '../api/auth'

/**
 * Cle de stockage du sessionStorage — cible par defaut de la session
 * (detruite a la fermeture du navigateur).
 *
 * <p>
 * Nommage prefixe par l'application : le sessionStorage est partage avec
 * toutes les applications du meme onglet, et un nom generique comme
 * {@code 'token'} risquerait d'ecraser une donnee d'un autre site.
 * </p>
 */
const STORAGE_KEY = 'expenseflow'

/**
 * Cle de stockage du localStorage — utilisee UNIQUEMENT si l'utilisateur
 * coche « Se souvenir de moi » sur la page de connexion.
 */
const STORAGE_LOCAL_KEY = 'expenseflow.local'

/**
 * Etat global de l'authentification, persiste en sessionStorage (defaut)
 * ou en localStorage si « Se souvenir de moi ».
 *
 * <p>
 * <strong>Pourquoi sessionStorage par defaut</strong> : le
 * sessionStorage est isole par onglet et detruit a la fermeture du
 * navigateur. Un jeton y survit donc a un rechargement de page — ce qui evite
 * de deconnecter l'utilisateur au moindre F5 — sans survivre au navigateur
 * lui-meme. Le localStorage n'est choisi que sur choix explicite de
 * l'utilisateur (case « Se souvenir de moi ») : il expose alors le jeton de
 * facon persistante a tout script injecte dans la page (XSS) — arbitrage
 * assume, sous son controle, avec un jeton qui expire cote serveur.
 * </p>
 *
 * <p>
 * <strong>Restauration synchrone avant le premier rendu.</strong> Zustand
 * execute le second argument de {@code create} au chargement du module, donc
 * <em>avant</em> que React ne monte le moindre composant. La valeur lue est
 * injectee dans l'etat initial, et {@code ProtectedRoute} voit donc un jeton
 * des son premier rendu. Avec le middleware {@code persist}, la restauration
 * se fait dans un {@code effect} : le premier rendu observerait un etat vide
 * et redirigerait vers {@code /login} avant meme que la restauration n'ait
 * lieu. C'est la raison du choix d'une lecture synchrone manuelle.
 * </p>
 *
 * <p>
 * <strong>Un jeton peut avoir expire entre-temps.</strong> Il n'est pas decode
 * ici : la premiere requete API renverra 401, et l'intercepteur declenchera
 * la deconnexion. Verifier l'expiration ici serait dupliquer la logique de
 * {@code JwtService} en JavaScript, avec le risque de diverger.
 * </p>
 *
 * @typedef {object} AuthState
 * @property {string|null} token      jeton courant, null si deconnecte
 * @property {UserResponse|null} user  profil courant, null si deconnecte
 * @property {boolean} loading        connexion en cours
 * @property {(email: string, motDePasse: string, rememberMe?: boolean) => Promise<AuthResponse>} login
 * @property {() => void} logout
 * @property {(user: UserResponse) => void} setUser
 * @property {() => boolean} isAuthenticated
 */

/**
 * Lit la session depuis le storage : localStorage d'abord (rememberMe),
 * puis sessionStorage.
 *
 * <p>
 * Entourée d'un {@code try/catch} : un sessionStorage corrompu (JSON invalide,
 * donnee ecrite par une version anterieure) ne doit pas empecher
 * l'application de demarrer — on considere alors l'utilisateur deconnecte.
 * </p>
 *
 * @returns {{ token: string|null, user: object|null }} session restauree
 */
function lireSession() {
  try {
    const brut =
      window.localStorage.getItem(STORAGE_LOCAL_KEY) ??
      window.sessionStorage.getItem(STORAGE_KEY)

    if (!brut) {
      return { token: null, user: null }
    }

    const { token, user } = JSON.parse(brut)

    // Un objet sans jeton est traite comme une session absente : sans jeton,
    // ProtectedRoute redirigerait de toute facon, et conserver un `user`
    // orphelin afficherait un nom dans le header alors que la session est
    // fermee.
    if (!token) {
      return { token: null, user: null }
    }

    return { token, user: user ?? null }
  } catch {
    return { token: null, user: null }
  }
}

/**
 * Ecrit la session dans le storage choisi (ou l'efface si token null).
 *
 * <p>
 * rememberMe = false → sessionStorage (defaut) ; rememberMe = true →
 * localStorage, uniquement sur choix explicite de l'utilisateur.
 * </p>
 *
 * @param {string|null} token    jeton a conserver, null pour effacer
 * @param {object|null} user     profil associe
 * @param {boolean} [rememberMe] true pour ecrire en localStorage
 * @returns {void}
 */
function ecrireSession(token, user, rememberMe = false) {
  try {
    if (!token) {
      // Deconnexion : les deux cles sont effacees, pour ne laisser aucun
      // jeton residuel (la case "Se souvenir de moi" peut avoir ete utilisee).
      window.localStorage.removeItem(STORAGE_LOCAL_KEY)
      window.sessionStorage.removeItem(STORAGE_KEY)
      return
    }

    const storage = rememberMe ? window.localStorage : window.sessionStorage
    const cle = rememberMe ? STORAGE_LOCAL_KEY : STORAGE_KEY
    storage.setItem(cle, JSON.stringify({ token, user }))
  } catch {
    // Mode navigation privee ou quota atteint : la session reste valable en
    // memoire pour l'onglet courant, elle survivra simplement pas au refresh.
  }
}

/**
 * <p>
 * <code>login</code> renvoie le resultat de <code>authApi.login</code> et
 * <strong>laisse remonter l'erreur</strong> au lieu de la stocker : le
 * composant appelant affiche l'erreur via <code>getErrorMessage(error)</code>.
 * Avaler l'erreur ici obligerait a ajouter un champ <code>error</code> au
 * store, avec le risque de conserver un message obsolete apres un succes.
 * </p>
 *
 * @type {import('zustand').UseBoundStore<AuthState>}
 */
export const useAuthStore = create((set, get) => ({
  ...lireSession(),
  loading: false,

  /**
   * Ouvre une session : appelle le backend puis memorise jeton et profil.
   *
   * <p>
   * {@code loading} est remis a faux dans les deux cas (succes comme echec) :
   * le bouton du formulaire doit toujours redevenir cliquable. En cas de
   * succes, la redirection vers l'application est ensuite decidee par le
   * composant, pas par le store.
   * </p>
   *
   * @param {string} email        adresse e-mail
   * @param {string} motDePasse   mot de passe
   * @param {boolean} rememberMe  true = ecrire en localStorage (case cochee)
   * @returns {Promise<AuthResponse>} reponse complete du backend
   * @throws {import('axios').AxiosError} 401 identifiants invalides, 400 donnees invalides
   */
  async login(email, motDePasse, rememberMe = false) {
    set({ loading: true })

    try {
      const { token, user } = await authApi.login(email, motDePasse)
      ecrireSession(token, user, rememberMe)
      set({ token, user, loading: false })
      return { token, user }
    } catch (error) {
      set({ loading: false })
      throw error
    }
  },

  /**
   * Ferme la session.
   *
   * <p>
   * Appelee a la fois par l'utilisateur (deconnexion volontaire) et par
   * l'intercepteur Axios sur reponse 401. Effacer les deux cles de stockage
   * (sessionStorage et localStorage) est indispensable : sans cela, le
   * prochain rechargement retablirait la session, annulant la deconnexion.
   * </p>
   */
  logout() {
    ecrireSession(null, null)
    set({ token: null, user: null, loading: false })
  },

  /**
   * Remplace le profil courant en memoire.
   *
   * <p>
   * Reserve au succes de {@code updateMyProfile} : le nouveau profil vient
   * du serveur et remplace l'ancien, le jeton n'est pas touche. La session
   * est reecrite dans le storage d'origine (localStorage si "Se souvenir de
   * moi", sinon sessionStorage) afin que le nom et l'e-mail mis a jour
   * survivent a un rechargement de page.
   * </p>
   *
   * @param {object} user profil enregistre renvoye par le backend
   */
  setUser(user) {
    const rememberMe = Boolean(window.localStorage.getItem(STORAGE_LOCAL_KEY))
    ecrireSession(get().token, user, rememberMe)
    set({ user })
  },

  /**
   * @returns {boolean} true si un jeton est memorise
   */
  isAuthenticated() {
    return Boolean(get().token)
  }
}))
