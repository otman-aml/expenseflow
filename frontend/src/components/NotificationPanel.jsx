import { useNavigate } from 'react-router-dom'

import { markAllNotificationsAsRead, markNotificationAsRead } from '../api/notifications'

/**
 * Panneau de liste des notifications non lues.
 *
 * <p>
 * <strong>Chaque entrée est un lien vers la note concernée</strong>, lorsque
 * le serveur en a fourni un ({@code lien}). Une notification sans lien reste
 * affichée mais non cliquable : mieux vaut un message lu qu'un lien mort.
 * </p>
 *
 * <p>
 * <strong>Le marquage est optimiste.</strong> Clic sur une entrée : elle est
 * retiree de la liste locale immediatement, avant la reponse du serveur —
 * l'utilisateur ne doit pas attendre un aller-retour pour voir sa pastille
 * diminuer. Si l'appel echoue, la prochaine interrogation (polling) remet
 * l'etat reel en place : aucune synchronisation manuelle n'est tentee.
 * </p>
 *
 * @param {{ notifications: Array<object>, onMarkAsRead: (id: number) => void, onMarkAllAsRead: () => void, onNavigate: () => void }} props
 *        liste courante et rappels de mise a jour par le parent (le porteur
 *        de l'etat), plus la fermeture du panneau apres navigation.
 * @returns {React.ReactNode} panneau de liste
 */
function NotificationPanel({ notifications, onMarkAsRead, onMarkAllAsRead, onNavigate }) {
  const navigate = useNavigate()

  /**
   * Marque la notification comme lue, met a jour le porteur d'etat, puis
   * navigue vers sa note si le serveur en a fourni un lien.
   *
   * @param {object} notification notification cliquee
   */
  async function surClic(notification) {
    onMarkAsRead(notification.id)
    try {
      await markNotificationAsRead(notification.id)
    } catch {
      // Echec silencieux : le polling suivant resynchronise la liste avec
      // la realite serveur. Le porteur d'etat a deja retire l'entree, ce
      // qui reste le comportement le moins surprenant pour l'utilisateur.
    }
    if (notification.lien) {
      navigate(notification.lien)
      onNavigate()
    }
  }

  /**
   * Marque l'integralite comme lue d'un seul geste, sans navigation.
   */
  async function surClicToutMarquer() {
    onMarkAllAsRead()
    try {
      await markAllNotificationsAsRead()
    } catch {
      // Meme rationale que surClic : le polling corrige une divergence.
    }
  }

  return (
    <div
      role="menu"
      aria-label="Notifications"
      className="absolute right-0 mt-2 w-80 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg"
    >
      <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <p className="text-sm font-semibold text-slate-900">Notifications</p>
        {notifications.length > 0 && (
          <button
            type="button"
            onClick={surClicToutMarquer}
            className="text-xs font-medium text-brand-600 transition hover:text-brand-700"
          >
            Tout marquer comme lu
          </button>
        )}
      </div>

      {notifications.length === 0 ? (
        <p className="px-4 py-6 text-center text-sm text-slate-500">
          Aucune notification non lue.
        </p>
      ) : (
        <ul className="max-h-96 overflow-y-auto divide-y divide-slate-100">
          {notifications.map((notification) => (
            <li key={notification.id}>
              <button
                type="button"
                onClick={() => surClic(notification)}
                className="w-full px-4 py-3 text-left transition hover:bg-slate-50"
              >
                <span className="inline-flex items-start gap-2">
                  {/* Pastille de non-lecture : le point bleu signale ce qui
                      n'a pas encore ete vu, la colonne de texte reste alignee
                      sans lui. */}
                  <span
                    aria-hidden="true"
                    className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-brand-600"
                  />
                  <span>
                    <span className="block text-sm text-slate-800">
                      {notification.message}
                    </span>
                    <span className="mt-1 block text-xs text-slate-400">
                      {new Date(notification.createdAt).toLocaleString('fr-FR', {
                        dateStyle: 'short',
                        timeStyle: 'short'
                      })}
                    </span>
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default NotificationPanel