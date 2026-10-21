/**
 * Variantes de couleur acceptees par {@link StatCard}.
 *
 * <p>
 * Chaque variante associe un fond d'icone et une couleur de texte, deux
 * tonalites de la meme famille. Utiliser deux teintes d'une meme famille
 * (plutot qu'un fond vert et un texte jaune) garantit un contraste lisible :
 * c'est la seule regle appliquee par la liste, le reste etant laisse au choix
 * de l'appelant.
 * </p>
 *
 * @type {Record<string, string>}
 */
const VARIANTES = {
  gris: 'bg-slate-100 text-slate-600',
  jaune: 'bg-attente-50 text-attente-600',
  vert: 'bg-succes-50 text-succes-600',
  bleu: 'bg-brand-50 text-brand-600',
  rouge: 'bg-erreur-50 text-erreur-600'
}

/** Variante appliquee quand l'appelant n'en fournit pas. */
const VARIANTE_DEFAUT = VARIANTES.gris

/**
 * Carte d'indicateur chiffre pour le tableau de bord.
 *
 * <p>
 * <strong>Elle ne calcule rien.</strong> La valeur est deja formatee par
 * l'appelant via {@code formatMontant} ou {@code formatDate} : une carte qui
 * formate elle-meme devrait deviner le type de la valeur, et se tromperait sur
 * un nombre brut — « 1500 » affiche « 1.500 » sans unite, la ou un montant doit
 * porter « MAD ».
 * </p>
 *
 * <p>
 * <strong>L'icone est decorative</strong> et porte {@code aria-hidden} : elle
 * double l'information du texte, et la faire lire par un lecteur d'ecran
 * produirait « Notes en brouillon, fichier texte, 3 » — la meme information
 * enoncee deux fois.
 * </p>
 *
 * @param {{ icon: React.ComponentType<{size?: number, 'aria-hidden'?: boolean}>,
 *           label: string, value: string | number, variant?: string,
 *           subtitle?: string }} props icone, libelle, valeur et variante
 * @returns {React.ReactNode} carte d'indicateur
 */
function StatCard({ icon: Icone, label, value, variant = 'gris', subtitle }) {
  const couleurs = VARIANTES[variant] ?? VARIANTE_DEFAUT

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm transition hover:shadow-md">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-slate-500">{label}</p>
          {/* `tabular-nums` : les chiffres gardent une largeur constante, donc
              une carte qui se remplit ne fait pas sauter le nombre a chaque
              actualisation. */}
          <p className="mt-2 text-2xl font-bold tabular-nums tracking-tight text-slate-900">
            {value}
          </p>
        </div>

        <span
          className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl ${couleurs}`}
        >
          <Icone size={22} aria-hidden="true" />
        </span>
      </div>

      {subtitle && <p className="mt-3 text-xs text-slate-500">{subtitle}</p>}
    </div>
  )
}

export default StatCard