import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, FileText, ShieldCheck, Sparkles, Users } from 'lucide-react'

import { getStats } from '../api/admin'
import { useAuthStore } from '../stores/authStore'
import { formatDate } from '../utils/format'
import ErrorBanner from './ErrorBanner'
import Spinner from './Spinner'
import StatCard from './StatCard'

/**
 * Ordre d'affichage des roles dans la carte de repartition.
 *
 * <p>
 * L'enum {@code UserRole} commence par {@code EMPLOYEE} ; l'ordre ci-dessous
 * part du role administrateur pour lire la carte du plus global au plus
 * courant. Tous les roles y figurent, y compris absents de la table :
 * {@link CarteRoles} affiche 0 plutot qu'une ligne disparue.
 * </p>
 */
const ROLES = ['ADMIN', 'MANAGER', 'FINANCE_ADMIN', 'EMPLOYEE']

/** Libelles francais des roles, cotes a leur cle d'API. */
const LIBELLES_ROLES = {
  ADMIN: 'Administrateurs',
  MANAGER: 'Managers',
  FINANCE_ADMIN: 'Finance',
  EMPLOYEE: 'Employés'
}

/**
 * Libelles lisibles des actions de compte journalisees par l'administration.
 *
 * <p>
 * La cle est l'action brute stockee dans {@code audit_log.action} ; la valeur
 * est ce que lit l'administrateur. Une action inconnue (ajoutée plus tard)
 * retombe sur la cle brute plutot que de laisser un trou.
 * </p>
 */
const LIBELLES_ACTIONS = {
  COMPTE_CREE: 'Compte créé',
  COMPTE_MODIFIE: 'Compte modifié',
  COMPTE_SUPPRIME: 'Compte supprimé'
}


/**
 * Tableau de bord specifique au role ADMIN.
 *
 * <p>
 * <strong>Un ADMIN n'a pas de notes « à creer ».</strong> La vue employe
 * (brouillons, validations, remboursements) de {@code DashboardPage} decrit
 * le quotidien de celui qui depose des notes — un administrateur n'en depose
 * pas : il gere des comptes. Ce composant expose donc ce qu'il pilote :
 * les comptes, leur repartition, le volume d'entreprise et les dernieres
 * actions journalisees.
 * </p>
 *
 * <p>
 * Les chiffres viennent de {@code GET /api/admin/stats} (filtre
 * {@code hasRole('ADMIN')}) : ils portent sur toute la base, pas sur le
 * perimetre du connecte, ce que la vue employe ne pourrait pas fournir.
 * </p>
 *
 * @returns {React.ReactNode} tableau de bord administrateur
 */
function AdminDashboard() {
  const user = useAuthStore((state) => state.user)
  const [stats, setStats] = useState(null)
  const [chargement, setChargement] = useState(true)
  const [erreur, setErreur] = useState(null)

  /**
   * Charge les statistiques d'entreprise.
   *
   * <p>
   * Un echec n'est pas fatal : le bandeau propose de reessayer, et aucune
   * donnee partielle n'est affichee — des compteurs a zero pris pour des
   * chiffres reels induiraient l'administrateur en erreur.
   * </p>
   */
  const charger = useCallback(async () => {
    setChargement(true)
    setErreur(null)
    try {
      const resultat = await getStats()
      setStats(resultat)
    } catch {
      setErreur("Impossible de charger les statistiques de l'application.")
    } finally {
      setChargement(false)
    }
  }, [])

  useEffect(() => {
    charger()
  }, [charger])

  if (chargement) {
    return (
      <div className="flex justify-center py-20" aria-hidden="true">
        <Spinner />
      </div>
    )
  }

  if (erreur) {
    return (
      <div className="space-y-4">
        <ErrorBanner message={erreur} />
        <button
          type="button"
          onClick={charger}
          className="inline-flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700"
        >
          Réessayer
        </button>
      </div>
    )
  }

  const actions = stats?.recentActions ?? []

  return (
    <div className="space-y-8">
      {/* --- En-tete --- */}
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-slate-900">
          Bonjour, {user?.nom ?? 'administrateur'}
        </h1>
        <p className="mt-1 text-slate-600">Vue d'ensemble de l'application</p>
      </div>

      {/* --- Section 1 : indicateurs entreprise --- */}
      <section aria-labelledby="titre-indicateurs">
        <h2 id="titre-indicateurs" className="mb-4 text-xl font-semibold text-slate-900">
          Statistiques
        </h2>

        {/* sm:grid-cols-2 et lg:grid-cols-4 : sur mobile les cartes restent
            empilees, sur tablette deux par deux, sur quatre colonnes a partir
            du format portable. */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            icon={Users}
            label="Utilisateurs"
            value={stats?.totalUsers ?? 0}
            variant="bleu"
            subtitle="Comptes enregistrés"
          />
          <CarteRoles usersByRole={stats?.usersByRole ?? {}} />
          <StatCard
            icon={FileText}
            label="Notes de frais"
            value={stats?.totalExpenses ?? 0}
            variant="gris"
            subtitle="Toutes notes confondues"
          />
          <StatCard
            icon={Sparkles}
            label="Appels IA"
            value={stats?.totalAiCalls ?? 0}
            variant="vert"
            subtitle="30 derniers jours"
          />
        </div>
      </section>

      {/* --- Section 2 : dernieres actions admin --- */}
      <section aria-labelledby="titre-audit">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 id="titre-audit" className="text-xl font-semibold text-slate-900">
            Dernières actions admin
          </h2>
          <span className="text-sm text-slate-500">Journal d'audit des comptes</span>
        </div>

        {actions.length === 0 ? (
          <p className="rounded-xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center text-sm text-slate-500">
            Aucune action enregistrée pour le moment.
          </p>
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <caption className="sr-only">
                Les cinq dernières actions d'administration des comptes, de la plus récente à la
                plus ancienne
              </caption>
              <thead className="bg-slate-50">
                <tr>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    Date
                  </th>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    Action
                  </th>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    Utilisateur cible
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {actions.map((action) => (
                  <tr
                    key={`${action.date}-${action.action}-${action.cible}`}
                    className="transition hover:bg-slate-50"
                  >
                    <td className="whitespace-nowrap px-5 py-4 text-slate-600">
                      {formatDate(action.date)}
                    </td>
                    <td className="px-5 py-4 font-medium text-slate-900">
                      {LIBELLES_ACTIONS[action.action] ?? action.action}
                    </td>
                    {/* La cible porte le compte concerne (e-mail et role) tel
                        qu'il figure dans audit_log.details : le supprime n'a
                        plus de ligne users a resolver, ce texte reste donc la
                        seule source fidelle. */}
                    <td className="px-5 py-4 text-slate-600">{action.cible ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* --- Section 3 : actions rapides --- */}
      <section aria-labelledby="titre-actions-rapides">
        <h2 id="titre-actions-rapides" className="mb-4 text-xl font-semibold text-slate-900">
          Actions rapides
        </h2>

        <div className="flex flex-wrap gap-3">
          <Link
            to="/admin/users"
            className="inline-flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700"
          >
            <Users size={18} aria-hidden="true" />
            Gérer les utilisateurs
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </div>
      </section>
    </div>
  )
}


/**
 * Carte de repartition des comptes par role.
 *
 * <p>
 * Meme coque que {@link StatCard} (rayon, bordure, ombre, icone a droite),
 * mais un ensemble de lignes au lieu d'un chiffre unique : la repartition
 * est un profil, pas un total, et l'ecraser en un seul nombre ferait
 * disparaitre l'information. Les roles absents de la table s'affichent a 0.
 * </p>
 *
 * @param {{ usersByRole: Record<string, number> }} props comptages par role
 * @returns {React.ReactNode} carte de repartition
 */
function CarteRoles({ usersByRole }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm transition hover:shadow-md">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-slate-500">Répartition par rôle</p>
          <dl className="mt-2 space-y-1">
            {ROLES.map((role) => (
              <div key={role} className="flex items-center justify-between gap-3">
                <dt className="text-sm text-slate-600">{LIBELLES_ROLES[role]}</dt>
                <dd className="text-sm font-semibold tabular-nums text-slate-900">
                  {usersByRole[role] ?? 0}
                </dd>
              </div>
            ))}
          </dl>
        </div>

        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
          <ShieldCheck size={22} aria-hidden="true" />
        </span>
      </div>

      <p className="mt-3 text-xs text-slate-500">Comptes par profil</p>
    </div>
  )
}

export default AdminDashboard
