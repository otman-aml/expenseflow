import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { KeyRound, Lock, Mail, User as UserIcon } from 'lucide-react'

import * as usersApi from '../api/users'
import ErrorBanner from '../components/ErrorBanner'
import Spinner from '../components/Spinner'
import { useAuthStore } from '../stores/authStore'
import { getErrorMessage } from '../utils/errors'

/**
 * Schema des informations personnelles.
 *
 * <p>
 * Regles miroir du DTO {@code UpdateProfileRequest} : nom 2-150 caracteres,
 * adresse e-mail au format standard. Comme sur la connexion, ces messages
 * de prevention (Zod, avant envoi) ne reprennent volontairement pas les
 * tournures du backend, qui eux ne s'affichent qu'en constat apres rejet.
 * </p>
 */
const profilSchema = z.object({
  nom: z
    .string()
    .min(2, 'Le nom doit contenir au moins 2 caractères')
    .max(150, 'Le nom doit contenir au plus 150 caractères'),
  email: z
    .string()
    .min(1, 'Adresse e-mail requise')
    .email('Adresse e-mail invalide')
})

/**
 * Schema de changement de mot de passe.
 *
 * <p>
 * La confirmation est verifiee par un {@code refine} plutot que dans le
 * composant : l'erreur est ainsi portee par le champ
 * {@code confirmationMotDePasse}, affichee exactement sous lui, comme une
 * erreur de champ ordinaire.
 * </p>
 */
const motDePasseSchema = z
  .object({
    ancienMotDePasse: z.string().min(1, 'Ancien mot de passe requis'),
    nouveauMotDePasse: z
      .string()
      .min(8, 'Le mot de passe doit contenir au moins 8 caractères')
      .max(100, 'Le mot de passe doit contenir au plus 100 caractères'),
    confirmationMotDePasse: z.string().min(1, 'Confirmation requise')
  })
  .refine((v) => v.nouveauMotDePasse === v.confirmationMotDePasse, {
    message: 'Les deux mots de passe ne correspondent pas',
    path: ['confirmationMotDePasse']
  })

/**
 * Page « Mon profil » du compte courant.
 *
 * <p>
 * Deux formulaires independants : chaque carte soumet a sa propre route
 * {@code /api/users/me}. Le formulaire d'informations est pre-rempli avec
 * les donnees du store Zustand et, apres un succes, le nouveau profil
 * renvoye par le serveur est re-injecte dans le store ({@code setUser})
 * afin que le header affiche immediatement le nouveau nom.
 * </p>
 *
 * <p>
 * Les messages de succes (bandeau vert) disparaissent apres trois
 * secondes ; les erreurs restent jusqu'a la prochaine soumission via
 * {@code ErrorBanner}.
 * </p>
 *
 * @returns {React.ReactNode} page profil
 */
function ProfilePage() {
  const user = useAuthStore((state) => state.user)
  const setUser = useAuthStore((state) => state.setUser)

  const [erreurProfil, setErreurProfil] = useState('')
  const [succesProfil, setSuccesProfil] = useState('')
  const [erreurMdp, setErreurMdp] = useState('')
  const [succesMdp, setSuccesMdp] = useState('')

  // Le store est deja peuple a l'arrivee (session ouverte), le formulaire
  // est donc pre-rempli des que la page monte.
  const profilForm = useForm({
    resolver: zodResolver(profilSchema),
    mode: 'onSubmit',
    defaultValues: { nom: user?.nom ?? '', email: user?.email ?? '' }
  })

  const mdpForm = useForm({
    resolver: zodResolver(motDePasseSchema),
    mode: 'onSubmit'
  })

  // Autodepliage des messages de succes apres 3 s. Le timer est nettoye au
  // changement de message ou au demontage, pour ne jamais fermer le bandeau
  // qu'un nouveau succes vient d'ouvrir.
  useEffect(() => {
    if (!succesProfil) {
      return undefined
    }
    const timer = setTimeout(() => setSuccesProfil(''), 3000)
    return () => clearTimeout(timer)
  }, [succesProfil])

  useEffect(() => {
    if (!succesMdp) {
      return undefined
    }
    const timer = setTimeout(() => setSuccesMdp(''), 3000)
    return () => clearTimeout(timer)
  }, [succesMdp])

  /**
   * Soumission de la carte « Informations personnelles ».
   *
   * @param {{ nom: string, email: string }} valeurs champs valides par Zod
   * @returns {Promise<void>}
   */
  async function surEnregistrerProfil(valeurs) {
    setErreurProfil('')
    setSuccesProfil('')

    try {
      const profil = await usersApi.updateMyProfile(valeurs)
      setUser(profil)
      // Reset sur les valeurs serveur (normalisees : email en minuscules).
      profilForm.reset({ nom: profil.nom, email: profil.email })
      setSuccesProfil('Vos informations ont été enregistrées.')
    } catch (error) {
      setErreurProfil(getErrorMessage(error))
    }
  }

  /**
   * Soumission de la carte « Changer le mot de passe ».
   *
   * @param {{ ancienMotDePasse: string, nouveauMotDePasse: string, confirmationMotDePasse: string }} valeurs
   * @returns {Promise<void>}
   */
  async function surChangerMdp(valeurs) {
    setErreurMdp('')
    setSuccesMdp('')

    try {
      await usersApi.changeMyPassword(valeurs)
      // Rafraichit le profil : le serveur a repasse doitChangerMdp a false,
      // ce qui libere la navigation (garde AppLayout).
      const profil = await usersApi.getMyProfile()
      setUser(profil)
      mdpForm.reset()
      setSuccesMdp('Votre mot de passe a été changé.')
    } catch (error) {
      setErreurMdp(getErrorMessage(error))
    }
  }

  return (
    <div className="space-y-8">
      {/* --- En-tete --- */}
      <div>
        {user?.doitChangerMdp && (
          <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-medium text-amber-800">
            Veuillez changer votre mot de passe pour continuer à utiliser l'application.
          </div>
        )}
        <h1 className="text-3xl font-bold tracking-tight text-slate-900">Mon profil</h1>
        <p className="mt-1 text-slate-600">
          Gérez vos informations personnelles et votre mot de passe
        </p>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* --- Carte 1 : informations personnelles --- */}
        <section className="rounded-xl border border-slate-200 bg-white p-6">
          <div className="flex items-center gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
              <UserIcon size={20} aria-hidden="true" />
            </span>
            <div>
              <h2 className="text-lg font-semibold text-slate-900">Informations personnelles</h2>
              <p className="text-sm text-slate-500">Votre nom et votre adresse de connexion</p>
            </div>
          </div>

          {succesProfil && (
            <div className="mt-5 rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700">
              {succesProfil}
            </div>
          )}
          {erreurProfil && <ErrorBanner message={erreurProfil} />}

          <form onSubmit={profilForm.handleSubmit(surEnregistrerProfil)} className="mt-5 space-y-4" noValidate>
            <div>
              <label htmlFor="nom" className="block text-sm font-medium text-slate-700">
                Nom complet
              </label>
              <input
                id="nom"
                type="text"
                autoComplete="name"
                className="mt-1 block w-full rounded-lg border border-slate-300 py-2.5 pl-3 pr-3 text-sm shadow-sm transition focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                aria-invalid={Boolean(profilForm.formState.errors.nom)}
                aria-describedby={profilForm.formState.errors.nom ? 'nom-error' : undefined}
                {...profilForm.register('nom', { shouldFocus: true })}
              />
              {profilForm.formState.errors.nom && (
                <p id="nom-error" className="mt-1 text-sm text-erreur-600">
                  {profilForm.formState.errors.nom.message}
                </p>
              )}
            </div>

            <div>
              <label htmlFor="email" className="block text-sm font-medium text-slate-700">
                Adresse e-mail
              </label>
              <div className="relative mt-1">
                <Mail
                  size={17}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                  aria-hidden="true"
                />
                <input
                  id="email"
                  type="email"
                  autoComplete="email"
                  className="block w-full rounded-lg border border-slate-300 py-2.5 pl-10 pr-3 text-sm shadow-sm transition focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                  aria-invalid={Boolean(profilForm.formState.errors.email)}
                  aria-describedby={profilForm.formState.errors.email ? 'email-error' : undefined}
                  {...profilForm.register('email')}
                />
              </div>
              {profilForm.formState.errors.email && (
                <p id="email-error" className="mt-1 text-sm text-erreur-600">
                  {profilForm.formState.errors.email.message}
                </p>
              )}
            </div>

            <button
              type="submit"
              disabled={profilForm.formState.isSubmitting}
              className="flex w-full items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {profilForm.formState.isSubmitting ? (
                <>
                  <Spinner size="sm" />
                  Enregistrement...
                </>
              ) : (
                'Enregistrer'
              )}
            </button>
          </form>
        </section>

        {/* --- Carte 2 : changer le mot de passe --- */}
        <section className="h-fit rounded-xl border border-slate-200 bg-white p-6">
          <div className="flex items-center gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
              <KeyRound size={20} aria-hidden="true" />
            </span>
            <div>
              <h2 className="text-lg font-semibold text-slate-900">Changer le mot de passe</h2>
              <p className="text-sm text-slate-500">8 caractères minimum, vérifié par l'ancien</p>
            </div>
          </div>

          {succesMdp && (
            <div className="mt-5 rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700">
              {succesMdp}
            </div>
          )}
          {erreurMdp && <ErrorBanner message={erreurMdp} />}

          <form onSubmit={mdpForm.handleSubmit(surChangerMdp)} className="mt-5 space-y-4" noValidate>
            {/* --- ancien mot de passe --- */}
            <div>
              <label htmlFor="ancienMotDePasse" className="block text-sm font-medium text-slate-700">
                Ancien mot de passe
              </label>
              <div className="relative mt-1">
                <Lock
                  size={17}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                  aria-hidden="true"
                />
                <input
                  id="ancienMotDePasse"
                  type="password"
                  autoComplete="current-password"
                  className="block w-full rounded-lg border border-slate-300 py-2.5 pl-10 pr-3 text-sm shadow-sm transition focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                  aria-invalid={Boolean(mdpForm.formState.errors.ancienMotDePasse)}
                  aria-describedby={mdpForm.formState.errors.ancienMotDePasse ? 'ancien-error' : undefined}
                  {...mdpForm.register('ancienMotDePasse')}
                />
              </div>
              {mdpForm.formState.errors.ancienMotDePasse && (
                <p id="ancien-error" className="mt-1 text-sm text-erreur-600">
                  {mdpForm.formState.errors.ancienMotDePasse.message}
                </p>
              )}
            </div>

            {/* --- nouveau mot de passe --- */}
            <div>
              <label htmlFor="nouveauMotDePasse" className="block text-sm font-medium text-slate-700">
                Nouveau mot de passe
              </label>
              <div className="relative mt-1">
                <Lock
                  size={17}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                  aria-hidden="true"
                />
                <input
                  id="nouveauMotDePasse"
                  type="password"
                  autoComplete="new-password"
                  className="block w-full rounded-lg border border-slate-300 py-2.5 pl-10 pr-3 text-sm shadow-sm transition focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                  aria-invalid={Boolean(mdpForm.formState.errors.nouveauMotDePasse)}
                  aria-describedby={mdpForm.formState.errors.nouveauMotDePasse ? 'nouveau-error' : undefined}
                  {...mdpForm.register('nouveauMotDePasse')}
                />
              </div>
              {mdpForm.formState.errors.nouveauMotDePasse && (
                <p id="nouveau-error" className="mt-1 text-sm text-erreur-600">
                  {mdpForm.formState.errors.nouveauMotDePasse.message}
                </p>
              )}
            </div>

            {/* --- confirmation --- */}
            <div>
              <label htmlFor="confirmationMotDePasse" className="block text-sm font-medium text-slate-700">
                Confirmation du nouveau mot de passe
              </label>
              <div className="relative mt-1">
                <Lock
                  size={17}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                  aria-hidden="true"
                />
                <input
                  id="confirmationMotDePasse"
                  type="password"
                  autoComplete="new-password"
                  className="block w-full rounded-lg border border-slate-300 py-2.5 pl-10 pr-3 text-sm shadow-sm transition focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                  aria-invalid={Boolean(mdpForm.formState.errors.confirmationMotDePasse)}
                  aria-describedby={mdpForm.formState.errors.confirmationMotDePasse ? 'confirmation-error' : undefined}
                  {...mdpForm.register('confirmationMotDePasse')}
                />
              </div>
              {mdpForm.formState.errors.confirmationMotDePasse && (
                <p id="confirmation-error" className="mt-1 text-sm text-erreur-600">
                  {mdpForm.formState.errors.confirmationMotDePasse.message}
                </p>
              )}
            </div>

            <button
              type="submit"
              disabled={mdpForm.formState.isSubmitting}
              className="flex w-full items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {mdpForm.formState.isSubmitting ? (
                <>
                  <Spinner size="sm" />
                  Modification en cours...
                </>
              ) : (
                'Changer le mot de passe'
              )}
            </button>
          </form>
        </section>
      </div>
    </div>
  )
}

export default ProfilePage