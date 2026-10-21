import { Pencil, Trash2 } from 'lucide-react'

/**
 * Tableau des comptes, colonnes d'administration.
 *
 * <p>
 * Le rôle est affiché tel que reçu du serveur (enum en majuscules) : la
 * traduction visuelle resterait à synchroniser à chaque nouveau rôle,
 * alors que le libellé brut reste lisible et fidèle à ce qui est stocké.
 * </p>
 *
 * @param {{ comptes: Array<object>, edition: (compte: object) => void, suppression: (compte: object) => void, idCourant: number|null, enCours: number|null }} props
 *        liste, rappels d'édition/suppression, identifiant du compte en
 *        édition (surligné), et identifiant du compte dont la suppression
 *        est en vol (verrou anti double-clic).
 * @returns {React.ReactNode} tableau des comptes
 */
function UserTable({ comptes, edition, suppression, idCourant, enCours }) {
  // Une suppression en vol verrouille les actions : un second clic produirait
  // une confirmation suivie d'un 404 (« compte introuvable ») — le refus
  // réel (409) serait ainsi masqué par un artefact de rapidité de clic.
  const verrouille = enCours !== null && enCours !== undefined

  if (comptes.length === 0) {
    return (
      <p className="rounded-xl border border-slate-200 bg-white px-4 py-8 text-center text-sm text-slate-500">
        Aucun compte pour le moment.
      </p>
    )
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50">
          <tr>
            <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Nom</th>
            <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">E-mail</th>
            <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Rôle</th>
            <th scope="col" className="px-5 py-3 text-left font-semibold text-slate-700">Manager</th>
            <th scope="col" className="px-5 py-3 text-right font-semibold text-slate-700">Actions</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {comptes.map((compte) => (
            <tr
              key={compte.id}
              className={`transition hover:bg-slate-50 ${idCourant === compte.id ? 'bg-brand-50' : ''}`}
            >
              <td className="px-5 py-4 font-medium text-slate-900">{compte.nom}</td>
              <td className="px-5 py-4 text-slate-600">{compte.email}</td>
              <td className="px-5 py-4">
                <span className="inline-flex rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-700">
                  {compte.role}
                </span>
              </td>
              <td className="px-5 py-4 text-slate-600">
                {/* Sans manager : tiret explicite plutôt que cellule vide —
                    une case vide se lit comme une donnée manquante, alors
                    qu'ici l'absence est un cas nominal (premier niveau). */}
                {compte.managerNom ?? '—'}
              </td>
              <td className="px-5 py-4">
                <div className="flex items-center justify-end gap-1">
                  <button
                    type="button"
                    onClick={() => edition(compte)}
                    disabled={verrouille}
                    aria-label={`Modifier ${compte.nom}`}
                    className="rounded-lg p-2 text-slate-500 transition hover:bg-slate-100 hover:text-slate-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500"
                  >
                    <Pencil size={16} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    onClick={() => suppression(compte)}
                    disabled={verrouille}
                    aria-busy={enCours === compte.id}
                    aria-label={`Supprimer ${compte.nom}`}
                    className={`rounded-lg p-2 transition disabled:cursor-not-allowed disabled:opacity-40 ${
                      enCours === compte.id
                        ? 'animate-pulse text-red-600'
                        : 'text-slate-500 hover:bg-red-50 hover:text-red-600 disabled:hover:bg-transparent disabled:hover:text-slate-500'
                    }`}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default UserTable