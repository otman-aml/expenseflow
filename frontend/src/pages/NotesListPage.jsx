import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { PlusCircle, Receipt } from 'lucide-react'

import { listMyExpenses } from '../api/expenses'
import ErrorBanner from '../components/ErrorBanner'
import Spinner from '../components/Spinner'
import StatusBadge from '../components/StatusBadge'
import { getErrorMessage } from '../utils/errors'
import { formatDate, formatMontant } from '../utils/format'

/**
 * Filtres proposes, dans l'ordre ou ils sont affiches.
 *
 * <p>
 * <strong>Les groupes suivent la machine a etats, pas les libelles.</strong>
 * « En attente » regroupe {@code SUBMITTED}, {@code PENDING_MANAGER} et
 * {@code PENDING_FINANCE} : ce sont trois etats distincts pour l'utilisateur,
 * mais une seule reponse a la question « dois-je faire quelque chose ? » —
 * non. Les proposer separement enfermerait l'utilisateur dans un detail qui ne
 * le concerne pas.
 * </p>
 *
 * <p>
 * Les rejets sont absents : une note rejetee est definitively close et
 * n'appelle aucune action. Elle reste visible dans « Toutes ».
 * </p>
 *
 * @type {Array<{ cle: string, libelle: string, statuts: string[] | null }>}
 */
const FILTRES = [
  { cle: 'toutes', libelle: 'Toutes', statuts: null },
  { cle: 'brouillons', libelle: 'Brouillons', statuts: ['DRAFT'] },
  {
    cle: 'attente',
    libelle: 'En attente',
    statuts: ['SUBMITTED', 'PENDING_MANAGER', 'PENDING_FINANCE']
  },
  { cle: 'remboursees', libelle: 'Remboursées', statuts: ['REIMBURSED'] }
]

/** Nombre de notes affichees par page. */
const TAILLE_PAGE = 10

/**
 * Liste des notes de frais de l'utilisateur connecte.
 *
 * <p>
 * Quatre etats sont distingues explicitement — chargement, erreur, liste vide,
 * donnees — parce qu'ils appellent des rendus completement differents. Sans
 * l'etat vide, un utilisateur sans aucune note verrait un tableau vide sans
 * comprendre si la requete a echoue ou s'il n'a rien.
 * </p>
 *
 * <p>
 * <strong>Filtres et pagination sont cotes client.</strong> La liste entiere
 * tient en quelques kilo-octets ; la decouper cote serveur obligerait a
 * variante de requete et de pagination, sans eviter le transfert des lignes.
 * Le nombre affixe compte les notes filtrees, pas la totalite : c'est le
 * nombre que l'utilisateur s'attend a voir.
 * </p>
 *
 * @returns {React.ReactNode} liste des notes
 */
function NotesListPage() {
  const [notes, setNotes] = useState([])
  const [chargement, setChargement] = useState(true)
  const [erreur, setErreur] = useState('')
  const [filtre, setFiltre] = useState('toutes')
  const [page, setPage] = useState(0)

  useEffect(() => {
    // Flag de securite : sans lui, si le composant est demonte pendant la
    // requete (navigation rapide), l'etat serait mis a jour sur un composant
    //gone. React 18 le signale par un avertissement en developpement.
    let actif = true

    async function charger() {
      try {
        const resultat = await listMyExpenses()
        if (actif) {
          setNotes(resultat)
          setErreur('')
        }
      } catch (e) {
        if (actif) {
          setErreur(getErrorMessage(e))
        }
      } finally {
        if (actif) {
          setChargement(false)
        }
      }
    }

    charger()

    return () => {
      actif = false
    }
  }, [])

  const filtreActif = FILTRES.find((f) => f.cle === filtre)

  const notesFiltrees = useMemo(() => {
    if (!filtreActif?.statuts) {
      return notes
    }
    return notes.filter((note) => filtreActif.statuts.includes(note.statut))
  }, [notes, filtreActif])

  const nombrePages = Math.max(1, Math.ceil(notesFiltrees.length / TAILLE_PAGE))
  const notesAffichees = notesFiltrees.slice(page * TAILLE_PAGE, page * TAILLE_PAGE + TAILLE_PAGE)

  /**
   * Change de filtre et revient a la premiere page.
   *
   * <p>
   * Sans ce retour, selectionner un filtre sur la page 3 pourrait afficher un
   * tableau vide alors que des notes correspondent : le nombre de pages ayant
   * diminue, l'index courant serait hors bornes.
   * </p>
   *
   * @param {string} cle identifiant du filtre
   */
  function changerFiltre(cle) {
    setFiltre(cle)
    setPage(0)
  }

  if (chargement) {
    return (
      <div className="flex items-center justify-center py-20" role="status" aria-label="Chargement">
        <Spinner />
      </div>
    )
  }

  if (erreur) {
    return <ErrorBanner message={erreur} />
  }

  return (
    <div>
{/* --- En-tete --- */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Mes notes</h1>
          {/* Singulier pour un seul element : « 1 note » se lit mieux que
              « 1 notes », et l'ecart se remarque immediatement. */}
          <p className="mt-1 text-sm text-slate-500">
            {notesFiltrees.length === 1 ? '1 note' : `${notesFiltrees.length} notes`}
          </p>
        </div>

        {/* <Link> plutot qu'un <button> : la destination doit etre
            realement accessible (copier le lien, ouvrir dans un onglet),
            ce qu'un bouton javascript ne permet pas. */}
        <Link
          to="/notes/new"
          className="inline-flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700"
        >
          <PlusCircle size={18} aria-hidden="true" />
          Nouvelle note
        </Link>
      </div>
{/* --- Filtres ---
          `role="group"` et non « onglet » : ces boutons filtrent un tableau,
          ils ne changent pas de page. Un lecteur d'ecran annonçant « onglet »
          laisserait croire qu'on a quitte la page. */}
      <div
        role="group"
        aria-label="Filtrer les notes par statut"
        className="mb-4 flex flex-wrap gap-2"
      >
        {FILTRES.map((entree) => {
          const actif = entree.cle === filtre
          const nombre = entree.statuts
            ? notes.filter((note) => entree.statuts.includes(note.statut)).length
            : notes.length

          return (
            <button
              key={entree.cle}
              type="button"
              // aria-pressed expose l'etat actif au lecteur d'ecran : la
              // classe seule ne lui est pas transmise.
              aria-pressed={actif}
              onClick={() => changerFiltre(entree.cle)}
              className={`inline-flex items-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium transition ${
                actif
                  ? 'bg-brand-600 text-white shadow-sm'
                  : 'bg-white text-slate-600 ring-1 ring-slate-200 hover:bg-slate-50 hover:text-slate-900'
              }`}
            >
              {entree.libelle}
              <span
                className={`rounded-full px-1.5 py-0.5 text-xs tabular-nums ${
                  actif ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-500'
                }`}
              >
                {nombre}
              </span>
            </button>
          )
        })}
      </div>
      {notesFiltrees.length === 0 ? (
        <div className="mt-4 rounded-xl border border-dashed border-slate-300 bg-white px-6 py-14 text-center">
          <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-400">
            <Receipt size={26} aria-hidden="true" />
          </span>
          {/* Deux messages distincts : un filtre sans resultat et une absence
              totale de notes n'appellent pas la meme action. Proposer « creez
              votre premiere note » alors qu'il en existe quinze masquees par
              le filtre laisserait l'utilisateur sans issue. */}
          <p className="mt-4 font-medium text-slate-900">
            {notes.length === 0 ? 'Aucune note de frais' : 'Aucune note pour ce filtre'}
          </p>
          <p className="mt-1 text-sm text-slate-500">
            {notes.length === 0
              ? 'Créez votre première note pour commencer'
              : 'Modifiez le filtre pour voir vos autres notes'}
          </p>

          {notes.length === 0 && (
            <Link
              to="/notes/new"
              className="mt-6 inline-flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-brand-700"
            >
              <PlusCircle size={18} aria-hidden="true" />
              Nouvelle note
            </Link>
          )}
        </div>
      ) : (
        /* overflow-x-auto : le tableau garde ses colonnes et defile
           horizontalement sur mobile, plutot que d'etre tronque. Sans cela,
           la colonne 'Action' serait invisible sur un petit ecran. */
        <div className="mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <caption className="sr-only">
              Notes de frais, page {page + 1} sur {nombrePages}
            </caption>
            <thead className="bg-slate-50">
              <tr>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">
                  N°
                </th>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">
                  Date
                </th>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">
                  Catégorie
                </th>
                <th scope="col" className="px-5 py-3 text-right font-semibold text-slate-700">
                  Total
                </th>
                <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">
                  Statut
                </th>
                <th scope="col" className="px-5 py-3 text-right font-semibold text-slate-700">
                  Action
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {notesAffichees.map((note) => (
                <tr key={note.id} className="transition hover:bg-slate-50">
                  <td className="px-5 py-4 font-medium text-slate-900">#{note.id}</td>
                  <td className="whitespace-nowrap px-5 py-4 text-slate-600">
                    {formatDate(note.createdAt)}
                  </td>
                  <td className="px-5 py-4 text-slate-600">
                    {note.lignes?.[0]?.categorieNom ?? '—'}
                  </td>
                  <td className="whitespace-nowrap px-5 py-4 text-right font-medium tabular-nums text-slate-900">
                    {formatMontant(note.totalMontant)}
                  </td>
                  <td className="px-5 py-4">
                    <StatusBadge status={note.statut} />
                  </td>
                  <td className="px-5 py-4 text-right">
                    <Link
                      to={`/notes/${note.id}`}
                      className="font-medium text-brand-600 transition hover:text-brand-700"
                    >
                      Voir →
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* --- Pagination ---
          Affichee seulement s'il y a reellement plusieurs pages : sinon, un
          unique bouton desactive advertirait une pagination inexistante. */}
      {notesFiltrees.length > 0 && nombrePages > 1 && (
        <div className="mt-4 flex items-center justify-between gap-4">
          <p className="text-sm text-slate-500">
            Page {page + 1} sur {nombrePages}
          </p>

          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setPage((p) => p - 1)}
              disabled={page === 0}
              className="rounded-lg bg-white px-3.5 py-2 text-sm font-medium text-slate-700 ring-1 ring-slate-200 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Précédent
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              disabled={page >= nombrePages - 1}
              className="rounded-lg bg-white px-3.5 py-2 text-sm font-medium text-slate-700 ring-1 ring-slate-200 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Suivant
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

export default NotesListPage