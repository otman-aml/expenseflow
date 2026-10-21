import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle2, Eye } from 'lucide-react'

import { listByStatut } from '../api/expenses'
import ErrorBanner from './ErrorBanner'
import RejectModal from './RejectModal'
import Spinner from './Spinner'
import StatusBadge from './StatusBadge'
import { getErrorMessage } from '../utils/errors'
import { formatDate, formatMontant } from '../utils/format'

/**
 * File de notes de frais a traiter, partagee par les ecrans de validation.
 *
 * <p>
 * <strong>Pourquoi un composant generique</strong> : l'ecran manager et l'ecran
 * finance affichent la meme chose — une liste de notes dans un statut donne, avec
 * des actions. Seuls le statut filtre et les boutons changent. Dupliquer une
 * centaine de lignes pour ne differer que par deux proprietes aurait produit
 * deux listes a corriger independamment.
 * </p>
 *
 * <p>
 * <strong>Les actions sont des fonctions, pas des noms de verbes.</strong> Le
 * parent fournit le comportement ({@code approve}, {@code reimburse}) et ce
 * composant ne connait que la presentation. Impossible, donc, qu'un ecran
 * declenche l'appel d'un autre ecran.
 * </p>
 *
 * <p>
 * <strong>Le rejet se declare, il ne se deduit pas.</strong> Une action porte
 * {@code requiresComment: true} lorsqu'elle ouvre {@link RejectModal}. Lire ce
 * drapeau evite de faire dependre l'affichage de l'arite du handler, un detail
 * d'implementation qui ferait qu'une future action de consultation a deux
 * arguments ouvrirait la modale de rejet par erreur.
 * </p>
 *
 * @param {{
 *   statut: string,
 *   actions: Array<{
 *     label: string,
 *     variant?: 'primary' | 'danger',
 *     requiresComment?: boolean,
 *     handler: (id: number|string, commentaire?: string) => void,
 *     icone?: React.ComponentType<{ size?: number, className?: string }>
 *   }>,
 *   title?: string,
 *   subtitle?: string,
 *   emptyMessage?: string
 * }} props configuration de l'ecran
 * @returns {React.ReactNode} liste des notes du statut demande
 */
function ValidationsList({
  statut,
  actions,
  title = 'Validations en attente',
  subtitle,
  emptyMessage = 'Aucune note en attente.'
}) {
  const [notes, setNotes] = useState([])
  const [chargement, setChargement] = useState(true)
  const [erreur, setErreur] = useState('')
  const [actionEnCours, setActionEnCours] = useState(null)
  const [noteConcernee, setNoteConcernee] = useState(null)
  const [actionRejet, setActionRejeter] = useState(null)
  const [confirmation, setConfirmation] = useState('')

  const charger = useCallback(async () => {
    try {
      const resultat = await listByStatut(statut)
      setNotes(resultat)
      setErreur('')
    } catch (e) {
      setErreur(getErrorMessage(e))
    } finally {
      setChargement(false)
    }
  }, [statut])

  useEffect(() => {
    charger()
  }, [charger])

  /**
   * Declenche une action, ou ouvre la modale de rejet si elle en demande un.
   *
   * <p>
   * <strong>Le declencheur est explicite</strong> ({@code requiresComment}), et
   * non deduit du nombre de parametres du handler. L'arity aurait fonctionne,
   * mais elle fait dependre l'affichage d'un detail d'implementation : une
   * fonction de consultation a deux arguments aurait ouvert la modale de rejet
   * sans que personne n'ait rien demande.
   * </p>
   *
   * <p>
   * L'approbation est irreversible — la note quitte la file, et seul un autre
   * validateur peut la reprendre. D'ou la confirmation systeme sur les actions
   * qui n'en demandent pas.
   * </p>
   *
   * @param {object} note    note concernee
   * @param {object} action  action declaree par le parent
   */
  function executer(note, action) {
    if (action.requiresComment) {
      setActionRejeter(action)
      setNoteConcernee(note)
      return
    }

    const accepte = window.confirm(
      `${action.label} la note #${note.id} de ${note.employeeNom} (${formatMontant(note.totalMontant)}) ?`
    )
    if (accepte) {
      lancer(note, action)
    }
  }

  /**
   * Execute une action et recharge la liste.
   *
   * <p>
   * La liste est relue plutot que mise a jour localement : le nouvel etat
   * atteint depend du role appelant, et seule la base fait autorite.
   * </p>
   *
   * @param {object} note    note concernee
   * @param {object} action  action a executer
   */
  async function lancer(note, action) {
    setActionEnCours(note.id)
    try {
      await action.handler(note.id)
      setConfirmation(`Note #${note.id} traitee.`)
      setErreur('')
      await charger()
    } catch (e) {
      setErreur(getErrorMessage(e))
    } finally {
      setActionEnCours(null)
    }
  }

  /**
   * Rejette la note choisie, avec le motif saisi dans la modale.
   *
   * @param {string} commentaire motif du rejet
   */
  async function confirmerRejet(commentaire) {
    if (!noteConcernee || !actionRejet) {
      return
    }

    setActionEnCours(noteConcernee.id)
    try {
      await actionRejet.handler(noteConcernee.id, commentaire)
      setActionRejeter(null)
      setNoteConcernee(null)
      setConfirmation(`Note #${noteConcernee.id} rejetee.`)
      setErreur('')
      await charger()
    } catch (e) {
      setErreur(getErrorMessage(e))
    } finally {
      setActionEnCours(null)
    }
  }

  /** Classes Tailwind d'un bouton, choisi par variante. */
  const stylesVariante = {
    primary: 'bg-brand-600 text-white hover:bg-brand-700 focus:ring-brand-200',
    danger: 'border border-red-300 text-red-700 hover:bg-red-50 focus:ring-red-100'
  }

  if (chargement) {
    // Squelette plutot qu'un spinner : la file de validation est une page de
    // travail, pas une action ponctuelle. Un cercle Central invite a attendre
    // un resultat imminent ; des lignes fantomes montrent ce qui arrive, et
    // evitent que la page ne saute lorsque les donnees apparaissent.
    return (
      <div aria-hidden="true">
        <div className="h-8 w-56 animate-pulse rounded-lg bg-slate-200" />
        <div className="mt-3 h-4 w-72 animate-pulse rounded bg-slate-200" />
        <div className="mt-6 space-y-2">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-12 animate-pulse rounded-lg bg-slate-200" />
          ))}
        </div>
      </div>
    )
  }

  return (
    <div>
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">{title}</h1>
        {/* Compteur en pastille : donne l'ordre de grandeur d'un coup d'oeil,
            sans obliger a parcourir la colonne pour le recompter. */}
        <span className="inline-flex items-center rounded-full bg-slate-200 px-2.5 py-0.5 text-xs font-semibold tabular-nums text-slate-600">
          {notes.length} note{notes.length > 1 ? 's' : ''}
        </span>
      </div>

      {subtitle && <p className="mt-1 text-sm text-slate-600">{subtitle}</p>}

      {erreur && <ErrorBanner message={erreur} />}

      {confirmation && !erreur && (
        <div
          role="status"
          className="mt-6 rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700"
        >
          {confirmation}
        </div>
      )}

      {!erreur && notes.length === 0 && (
        <div className="mt-10 rounded-xl border border-dashed border-slate-300 bg-white px-6 py-14 text-center">
          {/* Une file vide est une bonne nouvelle — l'icone le dit, avant le
              texte. Sans elle, le bloc ressemblerait a une erreur de chargement. */}
          <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-succes-50 text-succes-600">
            <CheckCircle2 size={26} aria-hidden="true" />
          </span>
          <p className="mt-4 font-medium text-slate-900">{emptyMessage}</p>
        </div>
      )}

      {notes.length > 0 && (
        <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">N°</th>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Employé</th>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Dépenses</th>
                {/* Total aligne a droite : la seule colonne de nombres du
                    tableau, donc la seule qui se compare verticalement. */}
                <th scope="col" className="px-5 py-3 text-right font-semibold text-slate-700">Total</th>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Statut</th>
                <th scope="col" className="px-5 py-3 text-right font-semibold text-slate-700">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {notes.map((note) => {
                const occupe = actionEnCours === note.id
                return (
                  <tr key={note.id} className="transition hover:bg-slate-50">
                    <td className="px-5 py-4 font-medium tabular-nums text-slate-500">#{note.id}</td>
                    <td className="px-5 py-4 font-medium text-slate-800">{note.employeeNom}</td>
                    <td className="px-5 py-4 text-slate-600">
                      <span className="block whitespace-nowrap">
                        {formatDate(note.dateSoumission)}
                      </span>
                      <span className="text-xs text-slate-400">
                        {note.lignes.length} ligne{note.lignes.length > 1 ? 's' : ''}
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-5 py-4 text-right font-semibold tabular-nums text-slate-800">
                      {formatMontant(note.totalMontant)}
                    </td>
                    <td className="px-5 py-4">
                      <StatusBadge status={note.statut} />
                    </td>
                    <td className="px-5 py-4">
                      <div className="flex flex-wrap justify-end gap-2">
                        <Link
                          to={`/notes/${note.id}`}
                          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 transition hover:bg-slate-50"
                        >
                          <Eye size={14} aria-hidden="true" />
                          Voir
                        </Link>
                        {actions.map((action) => {
                          const primaire = action.variant === 'primary'
                          return (
                            <button
                              key={action.label}
                              type="button"
                              onClick={() => executer(note, action)}
                              disabled={occupe}
                              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition focus:outline-none focus:ring-2 disabled:opacity-50 ${
                                stylesVariante[action.variant ?? 'primary']
                              }`}
                            >
                              {occupe && primaire && <Spinner size="sm" />}
                              {/* L'icone est optionnelle : une action peut
                                  n'en avoir aucune. Le rendu conditionnel
                                  evite d'afficher un composant vide, qui
                                  laisserait un espace vide dans le bouton. */}
                              {action.icone && <action.icone size={14} aria-hidden="true" />}
                              {action.label}
                            </button>
                          )
                        })}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <RejectModal
        isOpen={actionRejet !== null}
        title={
          noteConcernee
            ? `${actionRejet?.label ?? 'Rejeter'} la note #${noteConcernee.id}`
            : 'Rejeter la note de frais'
        }
        onClose={() => {
          setActionRejeter(null)
          setNoteConcernee(null)
        }}
        onConfirm={confirmerRejet}
        busy={actionEnCours !== null && actionEnCours === noteConcernee?.id}
      />
    </div>
  )
}

export default ValidationsList