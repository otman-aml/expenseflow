import { useNavigate } from 'react-router-dom'

import { createExpense } from '../api/expenses'
import NoteForm from '../components/NoteForm'

/**
 * Page de creation d'une note de frais.
 *
 * <p>
 * Ce que cette page apporte a {@link NoteForm}, c'est la seule chose dont le
 * formulaire ne peut pas se charger : la redirection. Apres un POST, l'identifiant
 * de la note n'existe qu'apres l'appel ; c'est donc la page qui l'interroge,
 * puis qui envoie l'utilisateur sur la note fraichement creee — ou il peut la
 * consulter immediatement, encore en brouillon.
 * </p>
 *
 * @returns {React.ReactNode} formulaire de creation
 */
function NewNotePage() {
  const navigate = useNavigate()

  return (
    <NoteForm
      titre="Nouvelle note de frais"
      sousTitre="Saisissez une ou plusieurs depenses. Le total est calcule automatiquement."
      submitLabel="Enregistrer la note"
      onCancel={() => navigate('/notes')}
      onSubmit={async (payload) => {
        const creee = await createExpense(payload)
        navigate(`/notes/${creee.id}`, { replace: true })
      }}
    />
  )
}

export default NewNotePage