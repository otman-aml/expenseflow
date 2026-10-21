import { useEffect, useState } from 'react'
import { NavLink, matchPath, useLocation, useNavigate } from 'react-router-dom'
import {
  CheckSquare,
  FileText,
  LayoutDashboard,
  Menu,
  PlusCircle,
  Receipt,
  ShieldCheck,
  X
} from 'lucide-react'

import { useAuthStore } from '../stores/authStore'
import NotificationBell from './NotificationBell'
import UserMenu from './UserMenu'

/**
 * Ossature commune de toutes les pages authentifiees : en-tete et contenu.
 *
 * <p>
 * <strong>Le deconnexion vit ici, pas dans chaque page.</strong> La repetition
 * du bouton dans DashboardPage et NotesListPage aurait produit deux
 * implementations a maintenir, et le risque que l'une des deux oublie la
 * redirection. En le factorisant dans le layout, une seule modification
 * s'applique partout.
 * </p>
 *
 * <p>
 * <strong>La navigation est factorisee dans une liste.</strong> Chaque entree
 * definit son libelle et son chemin ; ajouter un ecran revient a ajouter une
 * ligne, sans dupliquer le {@code NavLink}.
 * </p>
 *
 * @param {{ children: React.ReactNode }} props contenu de la page
 * @returns {React.ReactNode} ossature de l'application
 */
function AppLayout({ children }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [menuMobileOuvert, setMenuMobileOuvert] = useState(false)
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)

  // Ferme le menu mobile a chaque navigation : le laisse ouvert ferait trainer
  // le panneau au-dessus de la page d'arrivee, masquee par lui, jusqu'a ce que
  // l'utilisateur reclique sur le bouton.
  useEffect(() => {
    setMenuMobileOuvert(false)
  }, [location.pathname])

  // Mot de passe provisoire non remplace : toute navigation est ramenee au
  // profil tant que le drapeau est actif. Seul le parcours utilisateur est
  // ferme — le backend n'est pas bloque.
  useEffect(() => {
    if (user?.doitChangerMdp && location.pathname !== '/profile') {
      navigate('/profile', { replace: true, state: { doitChangerMdp: true } })
    }
  }, [user?.doitChangerMdp, location.pathname, navigate])

  /**
   * Ferme la session et ramene a l'ecran de connexion.
   *
   * <p>
   * Le jeton vit dans {@code sessionStorage} (voir {@code authStore}) :
   * {@code logout()} doit aussi le retirer, sinon un refresh retablirait la
   * session juste apres une deconnexion volontaire.
   * </p>
   */
  function handleLogout() {
    logout()
    navigate('/login', { replace: true })
  }

  /**
   * Liens de navigation de base, communs a tous les roles connectes SAUF
   * l'ADMIN : a qui la partie employe (notes) est masquee.
   *
   * <p>
   * Un administrateur finance et un manager sont aussi des salaries : ils
   * declarent des depenses comme tout le monde. Leur retirer « Nouvelle note »
   * les priverait d'une fonction qu'ils exerce reellement.
   * </p>
   *
   * <p>
   * <strong>Le drapeau {@code end} separe une section de son exception.</strong>
   * Il ne suffit pas : voir {@link estActif} pour pourquoi une exclusion
   * explicite est necessaire. En resume, {@code end} est vrai pour la racine
   * et pour l'action « Nouvelle note », qui est un ecran a part entiere plutot
   * qu'un enfant de la liste des notes.
   * </p>
   *
   * @type {Array<{ to: string, label: string, end: boolean, exclure?: string }>}
   */
  /**
   * Determine si un lien de navigation doit etre surligne.
   *
   * <p>
   * <strong>Pourquoi ne pas se contenter du drapeau {@code end}.</strong> Par
   * defaut, un {@code NavLink} reste actif sur toute URL commencant par son
   * chemin. Comme {@code /notes} est un prefixe de {@code /notes/new},
   * « Mes notes » et « Nouvelle note » s'affichaient tous deux actifs sur
   * l'ecran de creation — deux onglets surlignes, dont aucun n'indique la page
   * reellement affichee.
   * </p>
   *
   * <p>
   * <strong>Pourquoi {@code end} ne suffit pas.</strong> Poser {@code end} sur
   * « Nouvelle note » resout la double surbrillance de cet onglet, mais pas
   * celle de « Mes notes » : il faut desactiver la section, pas l'action. Et
   * poser {@code end} sur « Mes notes » le desactiverait aussi sur
   * {@code /notes/12} et {@code /notes/12/edit}, qui font pourtant partie de
   * la section. Les quatre variantes ({@code end} true/false, motif
   * {@code /notes/*}, insensibilite a la casse) ont ete verifiees sur
   * {@code matchPath} : aucune ne satisfait les deux contraintes.
   * </p>
   *
   * <p>
   * D'ou {@code exclure} : le chemin sur lequel le lien doit s'eteindre malgre
   * le prefixe. Le calcul reste explicite et testable, plutot que de
   * bergantung d'un comportement later du routeur.
   * </p>
   *
   * @param {{ to: string, end: boolean, exclure?: string }} lien lien evalue
   * @param {string} url URL courante
   * @returns {boolean} true si le lien doit etre surligne
   */
  function estActif(lien, url) {
    if (lien.exclure && matchPath(lien.exclure, url)) {
      return false
    }
    return Boolean(matchPath({ path: lien.to, end: lien.end }, url))
  }

  const lienTableauDeBord = { to: '/', label: 'Tableau de bord', end: true, Icone: LayoutDashboard }

  // Liens « employe » : masques a l'administrateur. Un ADMIN ne declare pas
  // de depenses ; il ne voit donc que le tableau de bord et l'administration.
  const liensEmploye = [
    // `end: false` pour que la section reste active sur /notes/12 et
    // /notes/12/edit ; `exclure` pour qu'elle s'eteigne sur l'ecran de
    // creation, qui n'appartient pas a la liste des notes existantes.
    {
      to: '/notes',
      label: 'Mes notes',
      end: false,
      Icone: FileText,
      exclure: '/notes/new'
    },
    { to: '/notes/new', label: 'Nouvelle note', end: true, Icone: PlusCircle }
  ]

  /**
   * Liens supplementaires, reserves aux roles de validation.
   *
   * <p>
   * La finance figure dans la meme file que le manager : elle dispose du meme
   * droit d'approuver (section 2.1). Son ecart de remboursement arrive en 2.6.B ;
   * le lien correspondant n'est volontairement pas declare tant que la route
   * n'existe pas — un {@code NavLink} vers une route absente ramene a
   * l'accueil, et le lien semblerait alors casse.
   * </p>
   *
   * @type {Record<string, Array<{ to: string, label: string, end: boolean }>>}
   */
  const liensParRole = {
    MANAGER: [{ to: '/manager/validations', label: 'Validations', end: true, Icone: CheckSquare }],
    FINANCE_ADMIN: [
      { to: '/manager/validations', label: 'Validations', end: true, Icone: CheckSquare },
      { to: '/finance/validations', label: 'Remboursements', end: true, Icone: CheckSquare }
    ],
    EMPLOYEE: [],
    // Le lien n'existe que pour ADMIN : un NavLink visible pour tous
    // renverrait les autres roles vers une page que RoleRoute refuse
    // d'afficher — un lien qui mene a une page interdite est un bug visuel.
    ADMIN: [{ to: '/admin/users', label: 'Administration', end: true, Icone: ShieldCheck }]
  }

  // L'ADMIN n'obtient que le tableau de bord et ses liens de role :
  // la partie employe (notes) est exclue de sa navigation.
  const liens = [
    lienTableauDeBord,
    ...(user?.role === 'ADMIN' ? [] : liensEmploye),
    ...(liensParRole[user?.role] ?? [])
  ]

  /**
   * Un lien de navigation, avec son icone.
   *
   * <p>
   * La classe d'etat actif est partagee par les versions bureau et mobile : le
   * fond {@code bg-white/15} se lit sur le bandeau bleu, et la meme
   * transparence produit le meme contraste quelle que soit la largeur.
   * </p>
   *
   * @param {{ lien: object }} props lien a rendre
   * @returns {React.ReactNode} element NavLink
   */
  function LienNavigation({ lien }) {
    const { Icone } = lien
    const actif = estActif(lien, location.pathname)

    return (
      <NavLink
        to={lien.to}
        className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition ${
          actif ? 'bg-white/15 text-white' : 'text-white/80 hover:bg-white/10 hover:text-white'
        }`}
      >
        <Icone size={18} aria-hidden="true" className="shrink-0" />
        {lien.label}
      </NavLink>
    )
  }

  return (
    <div className="min-h-screen bg-slate-50">
      {/* `sticky top-0` : le bandeau reste visible pendant le defilement. Sur
          un tableau de notes, sans cela il faudrait remonter en haut de page
          pour changer d'ecran — un aller-retour a chaque navigation. */}
      <header className="sticky top-0 z-40 bg-brand-600 shadow-md">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-4 sm:px-6">
          {/* --- Zone gauche : logo et navigation bureau --- */}
          <NavLink to="/" className="flex shrink-0 items-center gap-2.5 text-white">
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/15">
              <Receipt size={20} aria-hidden="true" />
            </span>
            <span className="text-lg font-bold tracking-tight">ExpenseFlow</span>
          </NavLink>

          <nav
            className="hidden items-center gap-1 md:flex"
            aria-label="Navigation principale"
          >
            {liens.map((lien) => (
              <LienNavigation key={lien.to} lien={lien} />
            ))}
          </nav>

          {/* Espace elastique : repousse le bloc utilisateur a droite sans avoir a
              justifier le conteneur, quel que soit le nombre de liens. */}
          <div className="flex-1" />

          {/* --- Zone droite : cloche et menu utilisateur --- */}
          <div className="hidden items-center gap-1 md:flex">
            <NotificationBell />
          </div>
          <UserMenu user={user} onLogout={handleLogout} />

          {/* --- Menu mobile : bouton burger --- */}
          <button
            type="button"
            onClick={() => setMenuMobileOuvert((o) => !o)}
            aria-expanded={menuMobileOuvert}
            aria-controls="navigation-mobile"
            aria-label={menuMobileOuvert ? 'Fermer le menu' : 'Ouvrir le menu'}
            className="rounded-lg p-2 text-white transition hover:bg-white/10 md:hidden"
          >
            {menuMobileOuvert ? (
              <X size={22} aria-hidden="true" />
            ) : (
              <Menu size={22} aria-hidden="true" />
            )}
          </button>
        </div>

        {/* Panneau mobile. Il prolonge le bandeau plutot que de flotter
            par-dessus : le contenu glisse vers le bas au lieu d'ecraser la
            page, et la distinction reste lisible sur petit ecran. */}
        {menuMobileOuvert && (
          <nav
            id="navigation-mobile"
            className="border-t border-white/10 bg-brand-600 px-4 pb-4 pt-2 md:hidden"
            aria-label="Navigation mobile"
          >
            <div className="flex flex-col gap-1">
              {liens.map((lien) => (
                <LienNavigation key={lien.to} lien={lien} />
              ))}
            </div>
          </nav>
        )}
      </header>

      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">{children}</main>
    </div>
  )
}

export default AppLayout
