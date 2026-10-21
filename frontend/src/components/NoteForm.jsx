import { useEffect, useMemo, useState } from 'react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { PlusCircle, Save, Sparkles, Trash2 } from 'lucide-react'

import { extractExpense } from '../api/ai'
import { listCategories } from '../api/categories'
import ErrorBanner from './ErrorBanner'
import Spinner from './Spinner'
import { getErrorMessage } from '../utils/errors'
import { formatMontant } from '../utils/format'

/**
 * Schema de validation d'une ligne de depense.
 *
 * <p>
 * {@code dateDepense} est une chaine au format {@code YYYY-MM-DD}, celui de
 * l'input {@code type="date"} et de la colonne SQL {@code DATE}. Elle n'est
 * <strong>pas</strong> convertie en objet {@code Date} avant l'envoi : le
 * passer a {@code new Date()} decalerait la date d'un jour selon le fuseau,
 * transformant un depense du 27 en depense du 26.
 * </p>
 */
const ligneSchema = z.object({
  categorieId: z.coerce
    .number({ message: 'Catégorie requise' })
    .int()
    .positive('Catégorie requise'),
  montant: z.coerce
    .number({ message: 'Montant requis' })
    .positive('Le montant doit être supérieur à 0')
    // Plafond aligné sur la colonne SQL NUMERIC(10,2) : au-delà de
    // 99 999 999,99, le backend renvoie une erreur technique de
    // dépassement de précision, illisible pour l'utilisateur. On la
    // remplace ici par un message qui nomme le champ fautif.
    .max(99999999.99, 'Montant trop élevé (maximum 99 999 999,99 MAD)'),
  dateDepense: z
    .string()
    .min(1, 'Date de dépense requise')
    // Regex volontairement simple : le input type="date" impose deja le
    // format a l'utilisateur, cette regle ne protege que d'un collage
    // Manuel ou d'un appel direct.
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Date invalide'),
  description: z
    .string()
    .max(500, 'Description trop longue (500 caracteres maximum)')
    .optional()
    .or(z.literal(''))
})

/**
 * Schema de la note complete.
 *
 * <p>
 * {@code min(1)} fait correspondre l'obligation {@code @NotEmpty} du backend :
 * une note sans ligne n'a aucun sens, et le serveur la refuserait de toute
 * facon. On prefere le dire avant l'envoi.
 * </p>
 */
const noteSchema = z.object({
  lignes: z
    .array(ligneSchema)
    .min(1, 'Ajoutez au moins une ligne de depense')
})

/**
 * Ligne vide utilisee pour initialiser le tableau de lignes.
 *
 * <p>
 * Copiee a chaque ajout ({@code {...LIGNE_VIDE}}) : partager le meme objet
 * entre plusieurs lignes ferait que la saisie dans l'une modifierait l'autre.
 * </p>
 */
const LIGNE_VIDE = {
  categorieId: '',
  montant: '',
  dateDepense: '',
  description: ''
}

/**
 * Convertit une ligne de note existante en valeur de formulaire.
 *
 * <p>
 * <strong>Le montant est renvoye en chaine, pas en nombre.</strong> Les inputs
 * {@code type="number"} de React gèrent la valeur register comme une chaine ;
 * un nombre provoquerait un affichage « 100 » au lieu de « 100.00 », et ferait
 * perdre les zeros significatifs pendant la saisie. Le schema Zod applique
 * {@code z.coerce.number()} au moment de la validation, ce qui suffit.
 * </p>
 *
 * @param {object} ligne ligne telle que renvoyee par le backend
 * @returns {object} valeur prete pour le formulaire
 */
function versLigneDeFormulaire(ligne) {
  return {
    categorieId: String(ligne.categorieId ?? ''),
    montant: ligne.montant === null || ligne.montant === undefined ? '' : String(ligne.montant),
    dateDepense: ligne.dateDepense ?? '',
    description: ligne.description ?? ''
  }
}

/**
 * Formulaire de saisie des lignes d'une note de frais, en creation ou en edition.
 *
 * <p>
 * <strong>Pourquoi un seul composant pour les deux usages</strong> : creer une
 * note et modifier un brouillon demandent exactement la meme chose — les memes
 * champs, les memes regles, le meme total. Dupliquer ces deux cents lignes
 * ferait diverger les deux versions au premier correctif applique a l'une
 * seule, typiquement une regle de validation ou un libelle.
 * </p>
 *
 * <p>
 * <strong>Le composant ignore le sort de la note.</strong> Il appelle
 * {@code onSubmit} et attend sa resolution ; la redirection reste au parent, qui
 * seul sait si l'operation cree — et doit donc recuperer l'identifiant
 * attribue — ou modifie, ou l'identifiant est deja connu.
 * </p>
 *
 * <p>
 * <strong>Pre-remplissage par {@code defaultValues}</strong>, lu une seule fois
 * au montage : le parent ne doit donc monter ce composant qu'une fois la note
 * chargee, faute de quoi le formulaire s'initialiserait avec une ligne vide et
 * ne se reinitialiserait pas ensuite.
 * </p>
 *
 * @param {{
 *   titre: string,
 *   sousTitre?: string,
 *   initialData?: { lignes: Array<object> } | null,
 *   onSubmit: (payload) => Promise<void>,
 *   onCancel: () => void,
 *   submitLabel?: string,
 *   pendingLabel?: string
 * }} props configuration du formulaire
 * @returns {React.ReactNode} formulaire de saisie
 */
function NoteForm({
  titre,
  sousTitre,
  initialData = null,
  onSubmit: onSubmitExterne,
  onCancel,
  submitLabel = 'Enregistrer la note',
  pendingLabel = 'Enregistrement...'
}) {
  const [categories, setCategories] = useState([])
  const [chargementCategories, setChargementCategories] = useState(true)
  const [envoi, setEnvoi] = useState(false)
  const [erreurServeur, setErreurServeur] = useState('')

  // Brique d'extraction : etat propre au bloc IA, distinct de l'erreur du
  // formulaire. Un echec de l'extraction ne doit pas masquer une erreur de
  // validation, ni l'inverse.
  const [texteIa, setTexteIa] = useState('')
  const [extractionEnCours, setExtractionEnCours] = useState(false)
  const [erreurIa, setErreurIa] = useState('')
  const [succesIa, setSuccesIa] = useState('')

  /**
   * La brique n'est proposee qu'en creation.
   *
   * <p>
   * <strong>En edition, le formulaire est deja rempli</strong> : le
   * pre-remplissage par IA y reviendrait a ecraser des donnees saisies avec
   * une estimation, pour un gain nul. L'utilisateur modifie ce qu'il veut.
   * </p>
   */
  const iaDisponible = !initialData

  const {
    control,
    register,
    handleSubmit,
    setValue,
    formState: { errors }
  } = useForm({
    resolver: zodResolver(noteSchema),
    // Les valeurs initiales ne sont lues qu'au montage. Le parent garantit
    // que initialData est deja disponible : sinon le formulaire partirait
    // d'une ligne vide et ne se reinitialiserait pas a l'arrivee de la note.
    defaultValues: {
      lignes:
        initialData?.lignes?.length > 0
          ? initialData.lignes.map(versLigneDeFormulaire)
          : [{ ...LIGNE_VIDE }]
    }
  })

  const { fields, append, remove } = useFieldArray({ control, name: 'lignes' })

  // useWatch observe les lignes sans re-rendre tout le formulaire a chaque
  // frappe. getValues() ferait de meme mais sans declencher de rendu : le
  // total ne se mettrait jamais a jour.
  const lignes = useWatch({ control, name: 'lignes' })

  /**
   * Somme des montants saisis, pour l'affichage temps reel.
   *
   * <p>
   * Calculee sur les valeurs brutes et non sur les valeurs validees : le
   * total doit evoluer pendant la saisie, avant que le formulaire ne soit
   * valide. Les montants non numeriques (champ vide, ou encore en cours de
   * frappe) comptent pour 0 sans faire echouer le calcul.
   * </p>
   *
   * @returns {number} somme des montants
   */
  const total = useMemo(() => {
    return (lignes ?? []).reduce((somme, ligne) => {
      const montant = Number(ligne?.montant)
      return Number.isFinite(montant) ? somme + montant : somme
    }, 0)
  }, [lignes])

  useEffect(() => {
    let actif = true

    listCategories()
      .then((resultat) => {
        if (actif) {
          setCategories(resultat)
        }
      })
      .catch((e) => {
        if (actif) {
          setErreurServeur(getErrorMessage(e))
        }
      })
      .finally(() => {
        if (actif) {
          setChargementCategories(false)
        }
      })

    return () => {
      actif = false
    }
  }, [])

  /**
   * Valide puis delegue l'envoi au parent.
   *
   * <p>
   * L'erreur est absorbee ici plutot que propagee : le parent peut avoir
   * redirige, et une rejection non absorbee remonterait dans un gestionnaire
   * d'evenement sans qu'aucun message ne soit affiche.
   * </p>
   *
   * @param {{ lignes: Array<object> }} valeurs lignes deja valides par Zod
   * @returns {Promise<void>} resolu apres l'envoi
   */
  async function soumettre(valeurs) {
    setEnvoi(true)
    setErreurServeur('')

    try {
      await onSubmitExterne(valeurs)
    } catch (e) {
      setErreurServeur(getErrorMessage(e))
      setEnvoi(false)
    }
  }

  /**
   * Demande au modele de completer la premiere ligne du formulaire.
   *
   * <p>
   * <strong>Seule la premiere ligne est remplacee</strong>. Une extraction ne
   * porte que sur une depense : ecraser toutes les lignes aurait detruit la
   * saisie de l'utilisateur pour une seule description, et l'aurait fait
   * silencieusement.
   * </p>
   *
   * <p>
   * <strong>Le texte n'est pas efface apres l'appel.</strong> Il constitue la
   * trace de ce qui a produit la ligne ; l'utilisateur peut relancer apres
   * avoir corrige sa formulation, sans avoir a tout retaper.
   * </p>
   */
  async function preRemplirParIa() {
    if (texteIa.trim().length === 0) {
      return
    }

    setExtractionEnCours(true)
    setErreurIa('')
    setSuccesIa('')

    try {
      const { ligne } = await extractExpense(texteIa)

      setValue('lignes.0.categorieId', String(ligne.categorieId))
      setValue('lignes.0.montant', String(ligne.montant))
      setValue('lignes.0.dateDepense', ligne.dateDepense)
      setValue('lignes.0.description', ligne.description ?? '')

      setSuccesIa('Ligne complétée. Vérifiez-la avant d’enregistrer.')
    } catch (e) {
      // Le message est deja adapte a chaque code dans api/ai.js ; le
      // formulaire reste utilisable a la main.
      setErreurIa(e.message)
    } finally {
      setExtractionEnCours(false)
    }
  }

  const nbLignes = fields.length

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight text-slate-900">{titre}</h1>
      {sousTitre && <p className="mt-1 text-sm text-slate-600">{sousTitre}</p>}

      {erreurServeur && <ErrorBanner message={erreurServeur} />}

      {/* Erreur portant sur le tableau entier (pas sur une ligne precise) :
          elle n'a pas de champ ou se placer, d'ou un second bandeau. */}
      {errors.lignes?.message && <ErrorBanner message={errors.lignes.message} />}

      {iaDisponible && (
        <section
          className="mt-6 rounded-xl border border-brand-200 bg-brand-50/50 p-4"
          aria-labelledby="ia-titre"
        >
          <div className="flex items-start gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand-100 text-brand-600">
              <Sparkles size={20} aria-hidden="true" />
            </span>
            <div className="min-w-0">
              <h2 id="ia-titre" className="font-semibold text-brand-900">
                Décrire la dépense en langage naturel
              </h2>
              <p className="mt-0.5 text-sm text-brand-700">
                L&apos;extraction remplit la première ligne, que vous pourrez corriger
                avant d&apos;enregistrer.
              </p>
            </div>
          </div>

          <textarea
            value={texteIa}
            onChange={(e) => setTexteIa(e.target.value)}
            rows={2}
            maxLength={500}
            disabled={extractionEnCours}
            placeholder="Ex : J'ai paye 450 dh de taxi pour aller chez le client Acme le 14 mars"
            aria-label="Description de la dépense en langage naturel"
            className="mt-3 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:bg-slate-50"
          />

          <div className="mt-3 flex items-center gap-3">
            <button
              type="button"
              onClick={preRemplirParIa}
              disabled={extractionEnCours || texteIa.trim().length === 0}
              className="inline-flex items-center gap-2 rounded-lg border border-brand-300 bg-white px-4 py-2 text-sm font-medium text-brand-700 transition hover:bg-brand-50 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {extractionEnCours && <Spinner size="sm" />}
              {extractionEnCours ? 'Analyse en cours...' : 'Pré-remplir avec l’IA'}
            </button>

            {succesIa && (
              <p role="status" className="text-xs text-green-700">
                {succesIa}
              </p>
            )}
          </div>

          {erreurIa && <ErrorBanner message={erreurIa} />}
        </section>
      )}

      <form onSubmit={handleSubmit(soumettre)} className="mt-6 space-y-4" noValidate>
        {fields.map((champ, index) => {
          const erreurLigne = errors.lignes?.[index]

          return (
            <fieldset key={champ.id} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <legend className="px-2">
                {/* Numero en pastille : repere visuel indispensable dans un
                    ensemble de blocs identiques, ou seul le contenu
                    distingue une ligne de l'autre. */}
                <span className="inline-flex items-center rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-700">
                  Ligne {index + 1}
                </span>
              </legend>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor={`lignes.${index}.categorieId`} className="block text-sm font-medium text-slate-700">
                    Categorie
                  </label>
                  <select
                    id={`lignes.${index}.categorieId`}
                    disabled={chargementCategories}
                    className="mt-1 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                    {...register(`lignes.${index}.categorieId`)}
                  >
                    <option value="">
                      {chargementCategories ? 'Chargement...' : 'Choisir une catégorie'}
                    </option>
                    {categories.map((categorie) => (
                      <option key={categorie.id} value={categorie.id}>
                        {categorie.nom}
                      </option>
                    ))}
                  </select>
                  {erreurLigne?.categorieId && (
                    <p className="mt-1 text-sm text-red-600">{erreurLigne.categorieId.message}</p>
                  )}
                </div>

                <div>
                  <label htmlFor={`lignes.${index}.montant`} className="block text-sm font-medium text-slate-700">
                    Montant (MAD)
                  </label>
                  <input
                    id={`lignes.${index}.montant`}
                    type="number"
                    step="0.01"
                    min="0"
                    placeholder="0.00"
                    className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                    {...register(`lignes.${index}.montant`)}
                  />
                  {erreurLigne?.montant && (
                    <p className="mt-1 text-sm text-red-600">{erreurLigne.montant.message}</p>
                  )}
                </div>

                <div>
                  <label htmlFor={`lignes.${index}.dateDepense`} className="block text-sm font-medium text-slate-700">
                    Date de dépense
                  </label>
                  <input
                    id={`lignes.${index}.dateDepense`}
                    type="date"
                    className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                    {...register(`lignes.${index}.dateDepense`)}
                  />
                  {erreurLigne?.dateDepense && (
                    <p className="mt-1 text-sm text-red-600">{erreurLigne.dateDepense.message}</p>
                  )}
                </div>

                <div>
                  <label htmlFor={`lignes.${index}.description`} className="block text-sm font-medium text-slate-700">
                    Description <span className="font-normal text-slate-400">(optionnelle)</span>
                  </label>
                  <input
                    id={`lignes.${index}.description`}
                    type="text"
                    maxLength={500}
                    placeholder="Ex. Taxi vers l'aéroport"
                    className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100"
                    {...register(`lignes.${index}.description`)}
                  />
                  {erreurLigne?.description && (
                    <p className="mt-1 text-sm text-red-600">{erreurLigne.description.message}</p>
                  )}
                </div>
              </div>

              {/* Le bouton de suppression n'apparait que s'il reste au moins
                  une ligne : le schema impose min(1), et proposer de tout
                  supprimer pour obtenir un message d'erreur serait absurde. */}
              {nbLignes > 1 && (
                <button
                  type="button"
                  onClick={() => remove(index)}
                  aria-label={`Supprimer la ligne ${index + 1}`}
                  className="mt-3 inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm font-medium text-erreur-600 transition hover:bg-erreur-50"
                >
                  <Trash2 size={15} aria-hidden="true" />
                  Supprimer cette ligne
                </button>
              )}
            </fieldset>
          )
        })}

        <button
          type="button"
          onClick={() => append({ ...LIGNE_VIDE })}
          className="inline-flex items-center justify-center gap-2 rounded-lg border border-dashed border-slate-300 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 transition hover:border-brand-500 hover:bg-brand-50 hover:text-brand-600"
        >
          <PlusCircle size={17} aria-hidden="true" />
          Ajouter une ligne
        </button>

        {/* Total calcule cote client pour le confort de l'utilisateur. Le
            backend recalcule et fait foi : cette valeur n'est jamais envoyee,
            et le total affiche peut differer de celui du serveur si le JSON
            est falsifie. */}
        <div className="flex items-center justify-between rounded-xl border border-slate-200 bg-slate-50 px-5 py-4">
          <span className="text-base font-semibold text-slate-700">Total</span>
          {/* Le montant ressort en bleu de marque : c'est le chiffre que
              l'utilisateur cherche en arrivant au bas du formulaire. */}
          <span className="text-2xl font-bold tabular-nums tracking-tight text-brand-600">
            {formatMontant(total)}
          </span>
        </div>

        <div className="flex gap-3">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 shadow-sm transition hover:bg-slate-50"
          >
            Annuler
          </button>
          <button
            type="submit"
            disabled={envoi || chargementCategories}
            className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {envoi ? (
              <>
                <Spinner size="sm" />
                {pendingLabel}
              </>
            ) : (
              <>
                <Save size={17} aria-hidden="true" />
                {submitLabel}
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  )
}

export default NoteForm
