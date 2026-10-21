import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Eye, EyeOff, Lock, Mail, Receipt } from 'lucide-react'

import ErrorBanner from '../components/ErrorBanner'
import Spinner from '../components/Spinner'
import { useAuthStore } from '../stores/authStore'
import { getErrorMessage } from '../utils/errors'

/**
 * Cle localStorage pour l'e-mail "Se souvenir de moi".
 *
 * <strong>Seul l'e-mail est memorise ici, jamais le jeton.</strong> La case
 * accélère la prochaine connexion en pre-remplissant l'identifiant ; c'est
 * le store qui décide (rememberMe) si le jeton part en localStorage ou
 * reste en sessionStorage.
 */
const REMEMBER_EMAIL_KEY = 'expenseflow.remember-email'

/** Schema Zod : valide AVANT envoi — pas de 401 inutile sur un email invalide. */
const loginSchema = z.object({
  email: z.string().min(1, 'Adresse e-mail requise').email('Adresse e-mail invalide'),
  motDePasse: z.string().min(1, 'Mot de passe requis'),
  rememberMe: z.boolean().optional(),
})

function lireEmailMemorise() {
  try { return window.localStorage.getItem(REMEMBER_EMAIL_KEY) ?? '' }
  catch { return '' }
}

function memoriserEmail(email, rememberMe) {
  try {
    if (rememberMe) window.localStorage.setItem(REMEMBER_EMAIL_KEY, email)
    else window.localStorage.removeItem(REMEMBER_EMAIL_KEY)
  } catch { /* navigation privee */ }
}

/**
 * Page de connexion.
 *
 * Soumission : handleSubmit (RHF) valide d'abord via zodResolver, puis
 * n'appelle onSubmit que si tout est valide — saisie invalide = AUCUNE requete.
 * Le bouton est desactive par isSubmitting/loading pendant l'appel : double-clic
 * impossible => une seule requete par clic.
 *
 * Pas de boucle 401 : client.js n'a AUCUN retry, et son intercepteur de 401
 * ignore explicitement /auth/login. Un echec revient une fois et s'arrete.
 */
export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const login = useAuthStore((s) => s.login)
  const loading = useAuthStore((s) => s.loading)
  const from = location.state?.from?.pathname || '/'

  const [erreur, setErreur] = useState('')
  const [motDePasseVisible, setMotDePasseVisible] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: lireEmailMemorise(), rememberMe: Boolean(lireEmailMemorise()) },
  })

  const onSubmit = async (donnees) => {
    setErreur('')
    const { email, motDePasse, rememberMe } = donnees
    memoriserEmail(email, rememberMe)
    try {
      const { user } = await login(email, motDePasse, rememberMe)
      // Mot de passe provisoire (compte cree par un admin) : le changement
      // est impose avant toute autre page.
      if (user?.doitChangerMdp) {
        navigate('/profile', { replace: true, state: { doitChangerMdp: true } })
      } else {
        navigate(from, { replace: true })
      }
    } catch (error) {
      setErreur(getErrorMessage(error))
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-brand-50 to-slate-100 px-4 py-8">
      <div className="w-full max-w-md rounded-xl bg-white shadow-lg">
        <header className="flex items-center gap-2.5 rounded-t-xl bg-brand-600 px-6 py-4">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/15">
            <Receipt size={18} aria-hidden="true" />
          </span>
          <span className="text-lg font-bold tracking-tight text-white">ExpenseFlow</span>
        </header>

        <div className="px-6 py-6">
          {erreur && <ErrorBanner message={erreur} />}

          <form onSubmit={handleSubmit(onSubmit)} className="mt-4 space-y-4" noValidate>
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-slate-700">Adresse e-mail</label>
              <div className="relative mt-1">
                <Mail className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={18} aria-hidden="true" />
                <input
                  id="email"
                  type="email"
                  autoComplete="email"
                  placeholder="exemple@entreprise.ma"
                  className={`peer w-full rounded-lg border bg-slate-50 pl-10 pr-3 py-2 text-sm text-slate-800 outline-none transition focus-within:border-brand-600 focus-within:ring-1 focus-within:ring-brand-100 ${errors.email ? 'border-red-300' : 'border-slate-300'}`}
                  aria-invalid={errors.email ? 'true' : 'false'}
                  aria-describedby={errors.email ? 'email-error' : undefined}
                  {...register('email')}
                />
              </div>
              {errors.email && (
                <p id="email-error" className="mt-1 text-xs text-red-600" role="alert">
                  {errors.email.message}
                </p>
              )}
            </div>

            <div>
              <label htmlFor="motDePasse" className="block text-sm font-medium text-slate-700">Mot de passe</label>
              <div className="relative mt-1">
                <Lock className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={18} aria-hidden="true" />
                <input
                  id="motDePasse"
                  type={motDePasseVisible ? 'text' : 'password'}
                  autoComplete="current-password"
                  placeholder="Votre mot de passe"
                  className={`peer w-full rounded-lg border bg-slate-50 pl-10 pr-12 py-2 text-sm text-slate-800 outline-none transition focus-within:border-brand-600 focus-within:ring-1 focus-within:ring-brand-100 ${errors.motDePasse ? 'border-red-300' : 'border-slate-300'}`}
                  aria-invalid={errors.motDePasse ? 'true' : 'false'}
                  aria-describedby={errors.motDePasse ? 'pwd-error' : undefined}
                  {...register('motDePasse')}
                />
                <button
                  type="button"
                  onClick={() => setMotDePasseVisible((p) => !p)}
                  className="absolute inset-y-0 right-0 flex items-center pr-3 text-slate-400 transition hover:text-slate-600"
                  aria-label={motDePasseVisible ? 'Masquer le mot de passe' : 'Afficher le mot de passe'}
                >
                  {motDePasseVisible ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
                </button>
              </div>
              {errors.motDePasse && (
                <p id="pwd-error" className="mt-1 text-xs text-red-600" role="alert">
                  {errors.motDePasse.message}
                </p>
              )}
            </div>

            <div className="flex items-center justify-between pt-1">
              <label htmlFor="rememberMe" className="flex items-center gap-2 text-sm text-slate-600">
                <input
                  id="rememberMe"
                  type="checkbox"
                  className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-600"
                  {...register('rememberMe')}
                />
                Se souvenir de moi
              </label>
              <Link
                to="/forgot-password"
                className="text-sm text-brand-600 transition hover:text-brand-700"
              >
                Mot de passe oublié ?
              </Link>
            </div>

            <button
              type="submit"
              disabled={isSubmitting || loading}
              className="flex w-full items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-600 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {isSubmitting || loading ? (
                <>
                  <Spinner size="sm" />
                  <span>Connexion…</span>
                </>
              ) : (
                'Se connecter'
              )}
            </button>
          </form>

          <p className="mt-6 border-t border-slate-100 pt-5 text-center text-sm text-slate-500">
            Pas encore de compte ?{' '}
            <a
              href="mailto:admin@expenseflow.ma"
              className="text-brand-600 hover:text-brand-700 font-medium"
            >
              Contactez votre administrateur
            </a>
          </p>
        </div>
      </div>
    </div>
  )
}
