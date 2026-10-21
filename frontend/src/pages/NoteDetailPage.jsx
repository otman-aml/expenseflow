import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'

import { getExpenseById } from '../api/expenses'
import ErrorBanner from '../components/ErrorBanner'
import RequestActions from '../components/RequestActions'
import Spinner from '../components/Spinner'
import StatusBadge from '../components/StatusBadge'
import { getErrorMessage } from '../utils/errors'
import { formatDate, formatMontant } from '../utils/format'

/**
 * Detail d'une note de frais et les actions possibles.
 *
 * <p>
 * Le chargement est fait ici et non dans la liste : la note est une ressource
 * independante, accessible par URL. Le bouton "Voir" pourrait donc ouvrir un
 * nouvel onglet, et l'URL partagee resterait fonctionnelle — ce que le
 * chargement parallele dans la page liste ne permettrait pas.
 * </p>
 *
 * <p>
 * <strong>La note est rechargee apres chaque action</strong>. Une soumission
 * fait passer la note en SUBMITTED puis, automatiquement, en PENDING_MANAGER :
 * seul le serveur sait dire ou en est la note — calculer le statut en local
 * donnerait systematiquement un ecart.
 * </p>
 *
 * @returns {React.ReactNode} detail de la note
 */
function NoteDetailPage() {
  const { id } = useParams()

  const [note, setNote] = useState(null)
  const [chargement, setChargement] = useState(true)
  const [erreur, setErreur] = useState('')

  const charger = useCallback(async () => {
    setChargement(true)
    setErreur('')

    try {
      const detail = await getExpenseById(id)
      setNote(detail)
    } catch (e) {
      setErreur(getErrorMessage(e))
    } finally {
      setChargement(false)
    }
  }, [id])

  useEffect(() => {
    // 'id' est une dependance : changer de note dans la meme page (clic sur
    // "Voir" depuis la liste) doit relancer le chargement.
    charger()
  }, [charger])

  if (chargement) {
    return (
      <div className="flex items-center justify-center py-20" role="status" aria-label="Chargement">
        <Spinner />
      </div>
    )
  }

  if (erreur) {
    return (
      <div>
        <Link to="/notes" className="text-sm font-medium text-brand-600 hover:text-brand-700">
          Retour a la liste
        </Link>
        <ErrorBanner message={erreur} />
      </div>
    )
  }

  // Filet de securite : sans lui, une reponse 200 au corps vide afficherait un
  // crash sur note.statut au lieu d'un message lisible.
  if (!note) {
    return (
      <div className="rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
        Note introuvable
      </div>
    )
  }

  return (
    <div>
      {/* Retour a la liste : toujours presente. Sans elle, remonter au bandeau
          serait le seul moyen de quitter l'ecran — un detour sur un ecran
          dont on vient de sortir. */}
      <Link
        to="/notes"
        className="inline-flex items-center gap-2 text-sm font-medium text-slate-500 transition hover:text-brand-600"
      >
        <ArrowLeft size={16} aria-hidden="true" />
        Retour à la liste
      </Link>

      {/* En-tete : identifiant, statut et date de creation. */}
      <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Note #{note.id}
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            Créée le {formatDate(note.createdAt)}
          </p>
        </div>
        <StatusBadge status={note.statut} />
      </div>

      <div className="mt-6 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <dl className="grid gap-5 sm:grid-cols-3">
          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Employé
            </dt>
            <dd className="mt-1 text-sm text-slate-900">{note.employeeNom}</dd>
          </div>

          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Date de soumission
            </dt>
            <dd className="mt-1 text-sm text-slate-900">
              {/* Null tant que la note est en DRAFT : l'afficher comme telle
                  evite qu'un utilisateur croie a un bug de chargement. */}
              {note.dateSoumission ? formatDate(note.dateSoumission) : 'Non soumise'}
            </dd>
          </div>

          {/* Le total est la seule information de cette carte que l'utilisateur
              cherche en priorité : elle ressort, le reste reste neutre. */}
          <div>
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Total
            </dt>
            <dd className="mt-1 text-2xl font-bold tabular-nums tracking-tight text-brand-600">
              {formatMontant(note.totalMontant)}
            </dd>
          </div>
        </dl>
      </div>

      {/* Barre d'actions : le composant ne rend rien si aucune action n'est
          possible (etat terminal, ou role sans competence). */}
      <div className="mt-6">
        <RequestActions note={note} onAction={charger} />
      </div>

      <div className="mt-6 overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50">
            <tr>
              <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Catégorie</th>
              <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Date de dépense</th>
              <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Description</th>
              {/* Montant aligne a droite : une colonne de nombres se compare
                  verticalement, ce que le bord droit expose immediatement. */}
              <th scope="col" className="px-5 py-3 text-right font-semibold text-slate-700">Montant</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {note.lignes.map((ligne) => (
              <tr key={ligne.id} className="transition hover:bg-slate-50">
                <td className="px-5 py-4 text-slate-700">{ligne.categorieNom}</td>
                <td className="whitespace-nowrap px-5 py-4 text-slate-700">
                  {formatDate(ligne.dateDepense)}
                </td>
                <td className="px-5 py-4 text-slate-600">{ligne.description || '—'}</td>
                <td className="whitespace-nowrap px-5 py-4 text-right font-medium tabular-nums text-slate-900">
                  {formatMontant(ligne.montant)}
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t border-slate-200 bg-slate-50">
            <tr>
              <td colSpan={3} className="px-5 py-4 text-right text-base font-semibold text-slate-700">
                Total
              </td>
              {/* Le total provient du serveur, jamais d'une somme recalculee
                  ici : c'est la valeur qui fait foi pour le remboursement. */}
              <td className="whitespace-nowrap px-5 py-4 text-right text-lg font-bold tabular-nums text-brand-600">
                {formatMontant(note.totalMontant)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>

    </div>
  )
}

export default NoteDetailPage