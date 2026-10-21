import { useCallback, useEffect, useRef, useState } from 'react'
import { Bell } from 'lucide-react'

import { getUnreadNotifications } from '../api/notifications'
import NotificationPanel from './NotificationPanel'

/**
 * Délai du rafraîchissement automatique, en millisecondes.
 *
 * <p>
 * 30 secondes : assez fréquent pour qu'un manager soit prévenu dans des
 * délais humains, assez espacé pour que quatre appels par minute restent
 * négligeables côté serveur et batterie. Le rafraîchissement se produit
 * aussi à l'ouverture du panneau et après chaque action de l'utilisateur,
 * ce qui couvre l'essentiel sans websockets.
 * </p>
 */
const POLLING_MS = 30000

/**
 * Cloche de notification, avec pastille de compteur et panneau de liste.
 *
 * <p>
 * <strong>La pastille n'affiche que le compteur réel</strong>, obtenu par
 * {@code GET /api/notifications} (liste des non lues). Zéro notification
 * non lue = pastille absente — un « 0 » permanent apprendrait rien et
 * occuperait la place.
 * </p>
 *
 * <p>
 * <strong>Le polling reprend quand l'onglet redevient visible.</strong> Un
 * onglet en arrière-plan verra son intervalle suspendu par le navigateur ;
 * l'événement {@code visibilitychange} relance un rafraîchissement immédiat
 * au retour, plutôt que d'attendre un délai théorique.
 * </p>
 */
function NotificationBell() {
  const [ouvert, setOuvert] = useState(false)
  const [notifications, setNotifications] = useState([])
  const conteneurRef = useRef(null)

  /**
   * Rafraîchit la liste des non lues depuis le serveur.
   *
   * <p>
   * Les erreurs sont avalées volontairement : la cloche est un confort
   * d'affichage, et une panne de l'API ne doit ni afficher de bandeau
   * d'erreur à chaque 30 secondes, ni empêcher l'utilisation du reste de
   * l'application. La liste précédente est conservée.
   * </p>
   */
  const rafraichir = useCallback(async () => {
    try {
      setNotifications(await getUnreadNotifications())
    } catch {
      // Réseau coupé ou 401 : on conserve l'état affiché. Un logout
      // démonte de toute façon l'arbre entier, cloche comprise.
    }
  }, [])

  // --- Polling : toutes les 30 s, plus au retour d'un onglet masqué ---
  useEffect(() => {
    rafraichir()

    const intervalle = setInterval(rafraichir, POLLING_MS)

    function surVisibilite() {
      if (document.visibilityState === 'visible') {
        rafraichir()
      }
    }
    document.addEventListener('visibilitychange', surVisibilite)

    return () => {
      clearInterval(intervalle)
      document.removeEventListener('visibilitychange', surVisibilite)
    }
  }, [rafraichir])

  // --- Fermeture au clic extérieur (clone de UserMenu) ---
  useEffect(() => {
    if (!ouvert) {
      return undefined
    }

    function surClicExterieur(evenement) {
      if (conteneurRef.current && !conteneurRef.current.contains(evenement.target)) {
        setOuvert(false)
      }
    }

    document.addEventListener('mousedown', surClicExterieur)
    return () => document.removeEventListener('mousedown', surClicExterieur)
  }, [ouvert])

  // --- Fermeture sur Échap ---
  useEffect(() => {
    if (!ouvert) {
      return undefined
    }

    function surEchap(evenement) {
      if (evenement.key === 'Escape') {
        setOuvert(false)
      }
    }

    document.addEventListener('keydown', surEchap)
    return () => document.removeEventListener('keydown', surEchap)
  }, [ouvert])

  /**
   * Retire localement une notification marquée lue, sans appel réseau —
   * celui-ci est fait par le panneau, qui gère aussi la navigation.
   *
   * @param {number} id identifiant de la notification
   */
  function retirerLocalement(id) {
    setNotifications((liste) => liste.filter((n) => n.id !== id))
  }

  /**
   * Vide localement la liste après « tout marquer comme lu ».
   */
  function viderLocalement() {
    setNotifications([])
  }

  const nonLues = notifications.length

  return (
    <div ref={conteneurRef} className="relative">
      <button
        type="button"
        onClick={() => setOuvert((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={ouvert}
        aria-label={
          nonLues > 0
            ? `Notifications, ${nonLues} non lue${nonLues > 1 ? 's' : ''}`
            : 'Notifications, aucune non lue'
        }
        className="relative rounded-lg p-2 text-white/80 transition hover:bg-white/10 hover:text-white focus-visible:bg-white/10 focus-visible:text-white"
      >
        <Bell size={20} aria-hidden="true" />

        {/* Pastille de compteur : absente tant qu'aucune notification n'est
            non lue. Le nombre est tronqué à 99+ pour que le badge ne
            déborde jamais du bouton. */}
        {nonLues > 0 && (
          <span
            aria-hidden="true"
            className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold leading-none text-white ring-2 ring-brand-600"
          >
            {nonLues > 99 ? '99+' : nonLues}
          </span>
        )}
      </button>

      {ouvert && (
        <NotificationPanel
          notifications={notifications}
          onMarkAsRead={retirerLocalement}
          onMarkAllAsRead={viderLocalement}
          onNavigate={() => setOuvert(false)}
        />
      )}
    </div>
  )
}

export default NotificationBell