import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { CheckCircle, Clock, FileText, PlusCircle, Receipt, TrendingUp } from 'lucide-react'

import { listMyExpenses } from '../api/expenses'
import AdminDashboard from '../components/AdminDashboard'
import StatCard from '../components/StatCard'
import StatusBadge from '../components/StatusBadge'
import { useAuthStore } from '../stores/authStore'
import { formatDate, formatMontant } from '../utils/format'

/**
 * Statuts qui demandent encore une intervention : la note est partie chez
 * quelqu'un, elle n'est donc plus du ressort de son auteur.
 */
const STATUTS_EN_ATTENTE = new Set(['SUBMITTED', 'PENDING_MANAGER', 'PENDING_FINANCE'])

/**
 * Formate la date du jour en toutes lettres : « Vendredi 9 octobre 2026 ».
 *
 * <p>
 * La locale {@code fr-MA} est fixee pour la meme raison que dans
 * {@code format.js} : sans elle, le rendu suit la langue du navigateur.
 * </p>
 */
const FORMAT_JOUR = new Intl.DateTimeFormat('fr-MA', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric'
})

/**
 * Tableau de bord : indicateurs cles et dernieres notes.
 *
 * <p>
 * <strong>Les chiffres sont calcules cote front, sur la liste deja
 * chargee.</strong> Un endpoint {@code /dashboard/stats} aurait ete plus
 * propre pour un tableau de bord portant sur l'ensemble de l'entreprise, mais
 * ici il ne fait que resumer les notes du connecte : le serveur renvoie deja
 * cette liste, et un second appel introduirait un second aller-retour pour
 * recomputer un total.
 * </p>
 *
 * <p>
 * <strong>Le total porte sur le mois en cours, pas sur les 30 derniers
 * jours.</strong> Un mois calendaire correspond a ce que dit le libelle, alors
 * qu'une fenetre glissante rendrait le chiffre « ce mois-ci » faux au milieu
 * du mois.
 * </p>
 *
 * <p>
 * <strong>Un ADMIN ne voit jamais cette vue.</strong> Ses notes ne
 * determinent pas son quotidien : le rendu bascule vers AdminDashboard,
 * qui interroge /api/admin/stats.
 * </p>
 *
 * @returns {React.ReactNode} contenu de la page d'accueil
 */
function DashboardPage() {
  const user = useAuthStore((state) => state.user)
  const [notes, setNotes] = useState([])
  const [chargement, setChargement] = useState(true)

  useEffect(() => {
    // Un ADMIN n'a pas de notes a charger : sa vue est AdminDashboard, qui
    // interroge /api/admin/stats. Sans ce garde, le rendu bascule declencherait
    // quand meme un appel /expense-requests sans lecteur.
    if (user?.role === 'ADMIN') {
      return undefined
    }

    // Annule la reponse si le composant est demonte avant la fin de l'appel :
    // sans cela, un etat mis a jour apres le demontage provoque l'avertissement
    // React « setState on unmounted component ».
    let annule = false

    async function charger() {
      try {
        const resultat = await listMyExpenses()
        if (!annule) {
          setNotes(resultat)
        }
      } catch {
        // Un tableau de bord vide est preferable a une page cassee : le lien
        // « Voir toutes mes notes » reste accessible, et l'utilisateur y
        // verra l'erreur reelle si elle vient de la liste. Echouer ici en
        // montrant une erreur serait disproportionne : le tableau de bord
        // resume, il ne porte pas de donnee irremplacable.
      } finally {
        if (!annule) {
          setChargement(false)
        }
      }
    }

    charger()
    return () => {
      annule = true
    }
  }, [user?.role])

  const statistiques = useMemo(() => {
    const debutMois = new Date()
    debutMois.setDate(1)
    debutMois.setHours(0, 0, 0, 0)

    const brouillons = notes.filter((note) => note.statut === 'DRAFT')
    const enAttente = notes.filter((note) => STATUTS_EN_ATTENTE.has(note.statut))
    const remboursees = notes.filter(
      (note) =>
        note.statut === 'REIMBURSED' && new Date(note.updatedAt ?? note.createdAt) >= debutMois
    )

    const total = remboursees.reduce((somme, note) => somme + Number(note.totalMontant ?? 0), 0)

    return { brouillons, enAttente, remboursees, total }
  }, [notes])

  const dernieres = notes.slice(0, 5)
  const aujourdhui = FORMAT_JOUR.format(new Date())

  // Tous les hooks sont deja executes au-dessus : la bascule se place apres
  // eux pour que le nombre de hooks reste identique si le role changeait en
  // cours de vie du composant (reconnexion a un autre compte).
  if (user?.role === 'ADMIN') {
    return <AdminDashboard />
  }

  if (chargement) {
    return <DashboardSkeleton />
  }

  return (
    <div className="space-y-8">
      {/* --- En-tete --- */}
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-slate-900">
          Bonjour, {user?.nom ?? 'utilisateur'}
        </h1>
        <p className="mt-1 text-slate-600">
          Voici un aperçu de vos notes de frais
        </p>
        <p className="mt-1 text-sm capitalize text-slate-400">{aujourdhui}</p>
      </div>

      {/* --- Indicateurs ---
          sm:grid-cols-2 et lg:grid-cols-4 : sur mobile les cartes restent
          empilees, sur tablette deux par deux, sur quatre colonnes a partir du
          format portable. */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          icon={FileText}
          label="Notes en brouillon"
          value={statistiques.brouillons.length}
          variant="gris"
          subtitle={statistiques.brouillons.length === 0 ? 'Rien à soumettre' : 'En cours de saisie'}
        />
        <StatCard
          icon={Clock}
          label="En attente de validation"
          value={statistiques.enAttente.length}
          variant="jaune"
          subtitle="Soumises, pas encore traitées"
        />
        <StatCard
          icon={CheckCircle}
          label="Remboursées ce mois"
          value={statistiques.remboursees.length}
          variant="vert"
          subtitle="Depuis le début du mois"
        />
        <StatCard
          icon={TrendingUp}
          label="Total remboursé"
          value={formatMontant(statistiques.total)}
          variant="bleu"
          subtitle="Sur le mois en cours"
        />
      </div>

      {/* --- Dernieres notes --- */}
      <section>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-xl font-semibold text-slate-900">Dernières notes</h2>

          {notes.length > 0 && (
            <Link
              to="/notes"
              className="text-sm font-medium text-brand-600 transition hover:text-brand-700"
            >
              Voir toutes mes notes →
            </Link>
          )}
        </div>

        {notes.length === 0 ? (
          <EtatVide />
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <caption className="sr-only">
                Les cinq dernières notes de frais, de la plus récente à la plus ancienne
              </caption>
              <thead className="bg-slate-50">
                <tr>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    N°
                  </th>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    Date
                  </th>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    Catégorie
                  </th>
                  <th scope="col" className="px-5 py-3 text-right font-medium text-slate-500">
                    Montant
                  </th>
                  <th scope="col" className="px-5 py-3 text-left font-medium text-slate-500">
                    Statut
                  </th>
                  <th scope="col" className="px-5 py-3 text-right font-medium text-slate-500">
                    <span className="sr-only">Action</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {dernieres.map((note) => (
                  <tr key={note.id} className="transition hover:bg-slate-50">
                    <td className="px-5 py-4 font-medium text-slate-900">#{note.id}</td>
                    <td className="whitespace-nowrap px-5 py-4 text-slate-600">
                      {formatDate(note.createdAt)}
                    </td>
                    <td className="px-5 py-4 text-slate-600">
                      {/* Une note sans ligne n'a pas de catégorie : une note
                          validée par le serveur en possède toujours, mais un
                          brouillon en cours de saisie peut n'en avoir aucune
                          encore. On affiche un tiret plutôt qu'un « undefined »
                          qui trahirait un bug. */}
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
                        className="text-sm font-medium text-brand-600 transition hover:text-brand-700"
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
      </section>
    </div>
  )
}

/**
 * Squelette affiche pendant le chargement des notes.
 *
 * <p>
 * <strong>Il reproduit la forme finale</strong> : quatre blocs de meme
 * hauteur que les cartes, puis un bloc large. Un simple « Chargement... » ferait
 * sauter toute la page vers le bas a l'arrivee des donnees, ce qui est plus
 * deroutant qu'une attente de quelques centaines de millisecondes.
 * </p>
 *
 * <p>
 * {@code animate-pulse} simule un balayage. Le rendu est {@code aria-hidden} :
 * un lecteur d'ecran n'a rien a dire d'un squelette, et annoncerait des
 * « cartes » vides.
 * </p>
 *
 * @returns {React.ReactNode} maquette de la page
 */
function DashboardSkeleton() {
  return (
    <div className="space-y-8" aria-hidden="true">
      <div>
        <div className="h-8 w-64 animate-pulse rounded-lg bg-slate-200" />
        <div className="mt-3 h-4 w-80 animate-pulse rounded bg-slate-200" />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((index) => (
          <div key={index} className="rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex items-start justify-between gap-3">
              <div className="w-full">
                <div className="h-4 w-32 animate-pulse rounded bg-slate-200" />
                <div className="mt-3 h-7 w-20 animate-pulse rounded bg-slate-200" />
              </div>
              <div className="h-11 w-11 shrink-0 animate-pulse rounded-xl bg-slate-200" />
            </div>
          </div>
        ))}
      </div>

      <div>
        <div className="mb-4 h-6 w-40 animate-pulse rounded bg-slate-200" />
        <div className="h-64 animate-pulse rounded-xl border border-slate-200 bg-white" />
      </div>
    </div>
  )
}

/**
 * Message affiche quand l'utilisateur n'a encore aucune note.
 *
 * @returns {React.ReactNode} bloc d'etat vide avec appel a l'action
 */
function EtatVide() {
  return (
    <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-14 text-center">
      <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-400">
        <Receipt size={26} aria-hidden="true" />
      </span>
      <p className="mt-4 font-medium text-slate-900">Aucune note de frais pour l'instant</p>
      <p className="mt-1 text-sm text-slate-500">
        Créez votre première note pour commencer
      </p>
      <Link
        to="/notes/new"
        className="mt-6 inline-flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-brand-700"
      >
        <PlusCircle size={18} aria-hidden="true" />
        Créer ma première note
      </Link>
    </div>
  )
}

export default DashboardPage