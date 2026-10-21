import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown, LogOut, User } from 'lucide-react'

/**
 * Menu deroulant de l'utilisateur connecte.
 *
 * <p>
 * <strong>Pourquoi un composant dedie plutot que du JSX dans
 * {@code AppLayout}.</strong> Ce menu porte trois comportements qui n'ont rien
 * a voir de la navigation : l'etat ouvert/ferme, la fermeture au clic exterieur
 * et la fermeture au clavier. Inline dans le layout, il faudrait le dupliquer a
 * chaque evolution, et il alourdirait un fichier qui n'a pas vocation a contenir
 * de la logique d'interaction.
 * </p>
 *
 * <p>
 * <strong>Fermeture au clic exterieur</strong> : un ecouteur {@code mousedown}
 * est pose sur le document, qui se retire au demontage. On ecrete sur
 * {@code mousedown} et non sur {@code click} : au clic, le menu se fermerait
 * puis se rouvrirait quand le bouton recevrait le focus, puisque le navigateur
 * donne le focus au bouton apres un clic. L'evenement {@code mousedown} precede
 * ce transfert de focus, ce qui rend le comportement fiable.
 * </p>
 *
 * <p>
 * <strong>Le clic dans le menu ne ferme pas</strong>. L'ecouteur verifie que la
 * cible est bien dans le conteneur. Sans cette verification, un simple clic
 * dans le menu le ferait disparaitre, ce que l'utilisateur ne comprendrait pas.
 * </p>
 *
 * @param {{ user: object, onLogout: () => void }} props utilisateur et action de deconnexion
 * @returns {React.ReactNode} bouton utilisateur et menu deroulant
 */
function UserMenu({ user, onLogout }) {
  const [ouvert, setOuvert] = useState(false)
  const conteneurRef = useRef(null)

  // Ferme le menu si le clic est intervenu a l'exterieur.
  useEffect(() => {
    if (!ouvert) {
      return undefined
    }

    function surClicExterieur(evenement) {
      if (conteneurRef.current && !conteneurRef.current.contains(evenement.target)) {
        setOuvert(false)
      }
    }

    // Ecoute sur le document et non sur un element : le clic peut tomber en
    // dehors du header, qu'il faut aussi intercepter.
    document.addEventListener('mousedown', surClicExterieur)
    return () => document.removeEventListener('mousedown', surClicExterieur)
  }, [ouvert])

  // La touche Echap ferme le menu. Sans elle, un utilisateur clavier resterait
  // piege : il ouvre le menu et ne dispose d'aucun moyen de le refermer sans
  // reprendre la souris.
  useEffect(() => {
    if (!ouvert) {
      return undefined
    }

    function surEchap(evenement) {
      if (evenement.key === 'Escape') {
        setOuvert(false)
      }
    }

    document.addEventListener('keydown', surEchap)
    return () => document.removeEventListener('keydown', surEchap)
  }, [ouvert])

  // Initiales : deux lettres. Une initiale unique donne un disque presque vide
  // sur un fond colore, sans indice sur la personne ; deux lettres produisent
  // un cercle rempli, lisible meme sur un petit avatar.
  // `split` gere un nom compose : "Employe Demo" donne "ED", pas "E".
  const initiales = (user?.nom ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((mot) => mot[0])
    .join('')
    .toUpperCase()

  const libelleRole = {
    EMPLOYEE: 'Employé',
    MANAGER: 'Manager',
    FINANCE_ADMIN: 'Administration finance'
  }[user?.role] ?? user?.role ?? ''

  return (
    <div ref={conteneurRef} className="relative">
      <button
        type="button"
        onClick={() => setOuvert((v) => !v)}
        // Ces deux attributs annoncent au lecteur d'ecran qu'il s'agit d'un
        // menu deroulant. Sans aria-haspopup, la fonction du bouton n'est pas
        // devinee ; sans aria-expanded, l'utilisateur ne sait pas s'il est
        // ouvert ou ferme.
        aria-haspopup="menu"
        aria-expanded={ouvert}
        className="flex items-center gap-3 rounded-lg py-1.5 pl-1.5 pr-2 text-left transition hover:bg-white/10 focus-visible:bg-white/10"
      >
        <span
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-white/20 text-sm font-semibold text-white"
          aria-hidden="true"
        >
          {initiales || '—'}
        </span>

        <span className="hidden sm:block">
          <span className="block text-sm font-medium leading-tight text-white">
            {user?.nom ?? '—'}
          </span>
          <span className="block text-xs leading-tight text-white/70">{libelleRole}</span>
        </span>

        <ChevronDown
          size={16}
          className={`shrink-0 text-white/70 transition-transform ${ouvert ? 'rotate-180' : ''}`}
          aria-hidden="true"
        />
      </button>

      {ouvert && (
        <div
          role="menu"
          // `absolute right-0` ancre le panneau au bord droit du bouton. Sur un
          // panneau plus large que son ancre, un ancrage a gauche deborderait
          // de l'ecran et la deconnexion deviendrait inatteignable.
          className="absolute right-0 mt-2 w-56 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 shadow-lg"
        >
          <div className="border-b border-slate-100 px-4 py-3">
            <p className="truncate text-sm font-medium text-slate-900">{user?.nom ?? '—'}</p>
            <p className="truncate text-xs text-slate-500">{user?.email ?? ''}</p>
          </div>

          {/* Le menu propose deux actions : l'acces au profil et la
              deconnexion, separees d'un filet. Le lien ferme le menu sur le
              clic — sans quoi le panneau resterait ouvert par-dessus la page
              de profil. */}
          <Link
            to="/profile"
            role="menuitem"
            onClick={() => setOuvert(false)}
            className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-slate-700 transition hover:bg-slate-50"
          >
            <User size={16} aria-hidden="true" />
            Mon profil
          </Link>

          <div className="my-1 border-t border-slate-100" />

          <button
            type="button"
            role="menuitem"
            onClick={onLogout}
            className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-erreur-600 transition hover:bg-erreur-50"
          >
            <LogOut size={16} aria-hidden="true" />
            Se déconnecter
          </button>
        </div>
      )}
    </div>
  )
}

export default UserMenu