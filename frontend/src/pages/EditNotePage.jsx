import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'

import { getExpenseById, update } from '../api/expenses'
import ErrorBanner from '../components/ErrorBanner'
import NoteForm from '../components/NoteForm'
import { getErrorMessage } from '../utils/errors'

/**
 * Page de modification d'une note de frais en brouillon.
 *
 * <p>
 * <strong>Le formulaire n'est monte qu'une fois la note chargee</strong>, et
 * jamais avant : {@link NoteForm} lit ses valeurs initiales au seul montage, et
 * un formulaire demarre avec une ligne vide ne se reinitialiserait pas quand la
 * note arriverait. Le rendu intermediaire ci-dessous n'est donc pas un simple
 * habillage — c'est une condition de fonctionnement.
 * </p>
 *
 * <p>
 * <strong>Une note qui n'est plus un brouillon n'a rien a faire ici</strong> :
 * l'utilisateur est renvoye vers le detail. Deux raisons de le faire cote client
 * plutot que d'attendre un 409 du serveur : le brouillon affiche ne correspondrait
 * a rien de modifiable, et un message « modification impossible » dans un
 * formulaire plein serait incomprehensible. Le serveur reste bien sur la seule
 * autorite — le renvoi n'est qu'un gain d'experience.
 * </p>
 *
 * @returns {React.ReactNode} formulaire de modification
 */
function EditNotePage() {
  const { id } = useParams()
  const navigate = useNavigate()

  const [note, setNote] = useState(null)
  const [chargement, setChargement] = useState(true)
  const [erreur, setErreur] = useState('')

  useEffect(() => {
    let actif = true

    getExpenseById(id)
      .then((resultat) => {
        if (!actif) {
          return
        }
        // Le statut est verifie avant l'affichage : le formulaire ne doit pas
        // laisser croire qu'une note soumise reste modifiable.
        if (resultat.statut !== 'DRAFT') {
          navigate(`/notes/${id}`, { replace: true })
          return
        }
        setNote(resultat)
      })
      .catch((e) => {
        if (actif) {
          setErreur(getErrorMessage(e))
        }
      })
      .finally(() => {
        if (actif) {
          setChargement(false)
        }
      })

    return () => {
      actif = false
    }
  }, [id, navigate])

  if (chargement) {
    // Squelette plutot qu'un spinner : le formulaire est la page entiere.
    // Un cercle Central ferait sauter la mise en page a l'arrivee des
    // donnees, et la zone de saisie deserving d'etre vue avant d'exister.
    return (
      <div aria-hidden="true">
        <div className="h-7 w-56 animate-pulse rounded-lg bg-slate-200" />
        <div className="mt-3 h-4 w-96 max-w-full animate-pulse rounded bg-slate-200" />
        <div className="mt-6 h-32 animate-pulse rounded-xl border border-slate-200 bg-slate-200" />
        <div className="mt-4 h-24 animate-pulse rounded-xl border border-slate-200 bg-slate-200" />
      </div>
    )
  }

  if (erreur) {
    return (
      <div>
        <ErrorBanner message={erreur} />
      </div>
    )
  }

  return (
    <>
      {/* Retour au detail. Place avant le titre rendu par NoteForm, pour la
          meme raison que sur la page de detail : le lien indique d'ou l'on
          vient, le titre dit ou l'on est. L'annulation du formulaire y mène
          aussi, ce qui rend le chemin de retour cohérent quel que soit le
          point de sortie — bouton « Annuler » ou navigation arrière. */}
      <Link
        to={`/notes/${id}`}
        className="mb-4 inline-flex items-center gap-2 text-sm font-medium text-slate-500 transition hover:text-brand-600"
      >
        <ArrowLeft size={16} aria-hidden="true" />
        Retour à la note
      </Link>

      <NoteForm
        titre={`Modifier la note #${id}`}
        sousTitre="Une note ne peut plus être modifiée une fois soumise. Toute modification remplace l&apos;intégralité des lignes."
        initialData={note}
        submitLabel="Enregistrer les modifications"
        pendingLabel="Enregistrement..."
        onCancel={() => navigate(`/notes/${id}`)}
        onSubmit={async (payload) => {
          // Le PUT remplace toutes les lignes : celles retirees du formulaire
          // disparaissent, et les restantes recoivent de nouveaux identifiants.
          await update(id, payload)
          navigate(`/notes/${id}`, { replace: true })
        }}
      />
    </>
  )
}

export default EditNotePage