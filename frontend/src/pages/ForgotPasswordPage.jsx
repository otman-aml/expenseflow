import { Link } from 'react-router-dom'
import { ArrowLeft, Mail, Receipt } from 'lucide-react'

/**
 * Page « Mot de passe oublié ».
 *
 * <p>
 * Aucune reinitialisation automatique : le projet ne stocke pas de jeton de
 * reinitialisation ni d'expediteur SMTP (hors périmètre MVP). La page guide
 * l'utilisateur vers l'administrateur, seul habilité a modifier un mot de
 * passe en base.
 * </p>
 */
export default function ForgotPasswordPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-brand-50 to-slate-100 px-4 py-8">
      <div className="w-full max-w-md rounded-xl bg-white shadow-lg">
        <header className="flex items-center gap-2.5 rounded-t-xl bg-brand-600 px-6 py-4">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/15">
            <Receipt size={18} aria-hidden="true" />
          </span>
          <span className="text-lg font-bold tracking-tight text-white">ExpenseFlow</span>
        </header>

        <div className="px-6 py-8">
          <div className="mb-4 flex h-10 w-10 items-center justify-center rounded-lg bg-brand-50">
            <Mail className="text-brand-600" size={20} aria-hidden="true" />
          </div>

          <h1 className="text-lg font-semibold text-slate-800">Mot de passe oublié</h1>

          <p className="mt-3 text-sm leading-relaxed text-slate-600">
            Pour des raisons de sécurité, la réinitialisation des mots de passe est
            gérée par un administrateur. Contactez-le en indiquant votre adresse
            e-mail professionnelle.
          </p>

          <Link
            to="/login"
            className="mt-6 inline-flex items-center gap-2 text-sm font-medium text-brand-600 transition hover:text-brand-700"
          >
            <ArrowLeft size={16} aria-hidden="true" />
            Retour à la connexion
          </Link>
        </div>
      </div>
    </div>
  )
}
