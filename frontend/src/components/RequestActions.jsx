import { useState } from 'react'
import { Link } from 'react-router-dom'

import {
  approve,
  reimburse,
  reject,
  rejectFinance,
  submit
} from '../api/expenses'
import ErrorBanner from './ErrorBanner'
import RejectModal from './RejectModal'
import Spinner from './Spinner'
import { useAuthStore } from '../stores/authStore'
import { getErrorMessage } from '../utils/errors'

/**
 * Actions possibles sur une note, deduites de son statut et du role du lecteur.
 *
 * <p>
 * <strong>Les droits ne sont pas dupliques ici</strong> : la regle reste celle
 * du serveur, et ce composant ne fait que ne pas proposer un bouton dont
 * l'appel echouerait systematiquement. Un employe ne voit pas « Approuver »
 * parce qu'il ne peut pas l'exercer — mais c'est le 403 du serveur, et non ce
 * composant, qui fait autorite.
 * </p>
 *
 * <p>
 * <strong>Le bouton d'un etat terminal n'est pas desactive, il est absent.</strong>
 * Un bouton grise « Approuver » sur une note deja_REMBOURSEEE invite a
 * wonder pourquoi il ne repond pas ; son absence explique l'etat.
 * </p>
 *
 * @param {{ note: object, onAction: (note) => void }} props note affichee et rappel apres action
 * @returns {React.ReactNode} barre d'actions, ou rien si aucune n'est possible
 */
function RequestActions({ note, onAction }) {
  const user = useAuthStore((state) => state.user)
  const [enCours, setEnCours] = useState(false)
  const [erreur, setErreur] = useState('')
  const [actionRejet, setActionRejet] = useState(null)

  const role = user?.role
  const estProprietaire = note.employeeId === user?.id
  const statut = note.statut

  // Le bouton « Modifier » n'appartient pas au cycle d'approbation : c'est un
  // lien, pas une action, d'ou son rendu a part. Il apparait pour le seul
  // proprietaire d'un brouillon — un validateur qui modifie le contenu d'une
  // note sortirait du principe « valider n'est pas editer ».
  let actions = []
  const modifiable = statut === 'DRAFT' && estProprietaire

  if (statut === 'DRAFT' && estProprietaire) {
    actions = [{ label: 'Soumettre', variant: 'primary', handler: () => submit(note.id) }]
  } else if (statut === 'PENDING_MANAGER' && (role === 'MANAGER' || role === 'FINANCE_ADMIN')) {
    actions = [
      { label: 'Approuver', variant: 'primary', handler: () => approve(note.id) },
      { label: 'Rejeter', variant: 'danger', requiresComment: true, handler: reject }
    ]
  } else if (statut === 'PENDING_FINANCE' && role === 'FINANCE_ADMIN') {
    actions = [
      { label: 'Rembourser', variant: 'primary', handler: () => reimburse(note.id) },
      { label: 'Rejeter', variant: 'danger', requiresComment: true, handler: rejectFinance }
    ]
  }

  if (actions.length === 0) {
    return null
  }

  /**
   * Execute une action, ou ouvre la modale si elle demande un motif.
   *
   * @param {object} action action a executer
   */
  function executer(action) {
    if (action.requiresComment) {
      setActionRejet(action)
      return
    }

    const accepte = window.confirm(`${action.label} la note #${note.id} ?`)
    if (accepte) {
      lancer(action)
    }
  }

  /**
   * Execute l'action et previent le parent, qui recharge la note.
   *
   * @param {object} action action a executer
   * @param {string} [commentaire] motif, pour un rejet
   */
  async function lancer(action, commentaire) {
    setEnCours(true)
    setErreur('')
    try {
      await action.handler(commentaire)
      // Le parent recharge : la note change de statut. Aucun calcul local
      // n'est fiable, le serveur reste l'autorite.
      onAction()
    } catch (e) {
      // Sans ce catch, un refus du serveur (409 plafond depasse, 403
      // transition interdite...) serait avale : aucun message, aucune
      // recharge, et l'utilisateur conclut que « le bouton ne fait rien ».
      // Le serveur reste l'autorite — on se contente d'echanger son refus
      // en un message lisible.
      setErreur(getErrorMessage(e))
    } finally {
      setEnCours(false)
    }
  }

  const stylesVariante = {
    primary: 'bg-brand-600 text-white hover:bg-brand-700 focus:ring-brand-200',
    danger: 'border border-red-300 text-red-700 hover:bg-red-50 focus:ring-red-100'
  }

  return (
    <>
      <div className="flex flex-wrap gap-2">
        {modifiable && (
          <Link
            to={`/notes/${note.id}/edit`}
            className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-slate-100"
          >
            Modifier
          </Link>
        )}

        {actions.map((action) => (
          <button
            key={action.label}
            type="button"
            onClick={() => executer(action)}
            disabled={enCours}
            className={`inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium transition focus:outline-none focus:ring-2 disabled:opacity-50 ${
              stylesVariante[action.variant]
            }`}
          >
            {enCours && action.variant === 'primary' && <Spinner size="sm" />}
            {action.label}
          </button>
        ))}
      </div>

      {/* Refus du serveur lors de la derniere action : le message est
          efface au lancer d'une action suivante (setErreur('') dans
          lancer), pas besoin de bouton de fermeture. */}
      {erreur && <ErrorBanner message={erreur} />}

      <RejectModal
        isOpen={actionRejet !== null}
        title={actionRejet ? `${actionRejet.label} la note #${note.id}` : 'Rejeter la note'}
        onClose={() => setActionRejet(null)}
        onConfirm={(commentaire) => {
          const action = actionRejet
          setActionRejet(null)
          lancer(action, commentaire)
        }}
        busy={enCours}
      />
    </>
  )
}

export default RequestActions