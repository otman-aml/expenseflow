/**
 * Indice d'activite anime.
 *
 * <p>
 * Aucun composant externe (shadcn/ui, Heroicons) n'est utilise : le SVG est
 * court, et evite une dependance de plus dans un projet qui n'en a besoin que
 * pour une seule icone.
 * </p>
 *
 * @param {{ size?: 'sm' | 'md' }} props taille duSpinner
 * @returns {React.ReactNode} spinner
 */
function Spinner({ size = 'md' }) {
  const taille = size === 'sm' ? 'h-4 w-4' : 'h-8 w-8'

  return (
    <svg
      className={`${taille} animate-spin text-current`}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
    </svg>
  )
}

export default Spinner