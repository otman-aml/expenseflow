import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'

/**
 * Schéma de création : tout est exigé, mot de passe compris.
 *
 * <p>
 * La règle « au moins un chiffre » reprend la contrainte backend
 * ({@code CreateUserRequest}) : valider pareil des deux côtés évite un
 * aller-retour serveur pour une faute de saisie que le navigateur savait
 * déjà détecter. Le serveur reste l'autorité — sa validation ne disparaît
 * pas pour autant.
 * </p>
 */
const schemaCreation = z.object({
  nom: z.string().trim().min(2, 'Le nom doit contenir au moins 2 caractères').max(150),
  email: z.string().trim().min(1, 'E-mail requis').email('Format d’e-mail invalide'),
  motDePasse: z.string()
    .min(8, 'Le mot de passe doit contenir au moins 8 caractères')
    .regex(/\d/, 'Le mot de passe doit contenir au moins un chiffre'),
  role: z.enum(['ADMIN', 'MANAGER', 'FINANCE_ADMIN', 'EMPLOYEE']),
  managerId: z.string()
})

/**
 * Schéma d'édition : le mot de passe disparaît — un administrateur ne
 * réinitialise jamais le secret d'un autre, il l'enverrait en clair dans
 * le payload.
 */
const schemaEdition = z.object({
  nom: z.string().trim().min(2, 'Le nom doit contenir au moins 2 caractères').max(150),
  email: z.string().trim().min(1, 'E-mail requis').email('Format d’e-mail invalide'),
  role: z.enum(['ADMIN', 'MANAGER', 'FINANCE_ADMIN', 'EMPLOYEE']),
  managerId: z.string()
})

/**
 * Formulaire de création et d'édition d'un compte.
 *
 * <p>
 * Un seul composant pour les deux modes : les champs sont identiques à
 * l'exception du mot de passe. Deux composants séparés auraient dupliqué
 * la moitié de leur corps pour diverger sur une seule ligne, et fait
 * glisser les deux versions à chaque évolution du schéma.
 * </p>
 *
 * @param {{ compte: object|null, managers: Array<{id: number, nom: string}>, soumission: (payload: object) => Promise<void>, annulation: () => void, enCours: boolean }} props
 *        compte à édition (null = création), liste des managers, rappels.
 * @returns {React.ReactNode} formulaire de compte
 */
function UserForm({ compte, managers, soumission, annulation, enCours }) {
  const modeEdition = compte !== null

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors }
  } = useForm({
    resolver: zodResolver(modeEdition ? schemaEdition : schemaCreation),
    defaultValues: { nom: '', email: '', motDePasse: '', role: 'EMPLOYEE', managerId: '' }
  })

  // Au changement de cible (création → édition d'un autre compte), les
  // valeurs sont réinitialisées : sans quoi le formulaire conserverait les
  // saisies du compte précédent, et la soumission modifierait par erreur
  // des champs que l'écran ne montre plus.
  useEffect(() => {
    if (modeEdition) {
      reset({
        nom: compte.nom,
        email: compte.email,
        role: compte.role,
        managerId: compte.managerId != null ? String(compte.managerId) : ''
      })
    } else {
      reset({ nom: '', email: '', motDePasse: '', role: 'EMPLOYEE', managerId: '' })
    }
  }, [compte, modeEdition, reset])

  /**
   * Convertit la soumission en payload : managerId vide → null (détachement),
   * sinon nombre.
   *
   * @param {object} valeurs valeurs brutes du formulaire
   */
  function surSoumission(valeurs) {
    const payload = {
      nom: valeurs.nom,
      email: valeurs.email,
      role: valeurs.role,
      managerId: valeurs.managerId === '' ? null : Number(valeurs.managerId)
    }
    if (!modeEdition) {
      payload.motDePasse = valeurs.motDePasse
    }
    soumission(payload)
  }

  return (
    <form
      onSubmit={handleSubmit(surSoumission)}
      noValidate
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
    >
      <h2 className="text-base font-semibold text-slate-900">
        {modeEdition ? `Modifier ${compte.nom}` : 'Nouveau compte'}
      </h2>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        {/* --- Nom --- */}
        <div>
          <label htmlFor="admin-nom" className="block text-sm font-medium text-slate-700">
            Nom complet
          </label>
          <input
            id="admin-nom"
            type="text"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
            aria-invalid={errors.nom ? 'true' : 'false'}
            {...register('nom')}
          />
          {errors.nom && <p className="mt-1 text-xs text-red-600">{errors.nom.message}</p>}
        </div>

        {/* --- E-mail --- */}
        <div>
          <label htmlFor="admin-email" className="block text-sm font-medium text-slate-700">
            Adresse e-mail
          </label>
          <input
            id="admin-email"
            type="email"
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
            aria-invalid={errors.email ? 'true' : 'false'}
            {...register('email')}
          />
          {errors.email && <p className="mt-1 text-xs text-red-600">{errors.email.message}</p>}
        </div>
      </div>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        {/* --- Mot de passe : création seule --- */}
        {!modeEdition && (
          <div>
            <label htmlFor="admin-mdp" className="block text-sm font-medium text-slate-700">
              Mot de passe
            </label>
            <input
              id="admin-mdp"
              type="password"
              autoComplete="new-password"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
              aria-invalid={errors.motDePasse ? 'true' : 'false'}
              {...register('motDePasse')}
            />
            {errors.motDePasse && (
              <p className="mt-1 text-xs text-red-600">{errors.motDePasse.message}</p>
            )}
          </div>
        )}

        {/* --- Rôle --- */}
        <div>
          <label htmlFor="admin-role" className="block text-sm font-medium text-slate-700">
            Rôle
          </label>
          <select
            id="admin-role"
            className="mt-1 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
            {...register('role')}
          >
            <option value="EMPLOYEE">Employé</option>
            <option value="MANAGER">Manager</option>
            <option value="FINANCE_ADMIN">Finance</option>
            <option value="ADMIN">Administrateur</option>
          </select>
          {errors.role && <p className="mt-1 text-xs text-red-600">{errors.role.message}</p>}
        </div>

        {/* --- Manager --- */}
        <div>
          <label htmlFor="admin-manager" className="block text-sm font-medium text-slate-700">
            Manager hiérarchique
          </label>
          <select
            id="admin-manager"
            className="mt-1 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
            {...register('managerId')}
          >
            <option value="">Aucun (premier niveau)</option>
            {managers.map((manager) => (
              <option key={manager.id} value={manager.id}>
                {manager.nom}
              </option>
            ))}
          </select>
          {errors.managerId && (
            <p className="mt-1 text-xs text-red-600">{errors.managerId.message}</p>
          )}
        </div>
      </div>

      <div className="mt-5 flex items-center justify-end gap-2">
        <button
          type="button"
          onClick={annulation}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50"
        >
          Annuler
        </button>
        <button
          type="submit"
          disabled={enCours}
          className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {enCours ? 'Envoi…' : modeEdition ? 'Enregistrer' : 'Créer le compte'}
        </button>
      </div>
    </form>
  )
}

export default UserForm
