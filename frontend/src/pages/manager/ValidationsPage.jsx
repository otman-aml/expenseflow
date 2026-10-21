import { useMemo } from 'react'
import { CheckCircle2, XCircle } from 'lucide-react'

import { approve, reject } from '../../api/expenses'
import ValidationsList from '../../components/ValidationsList'

/**
 * File des notes en attente de validation manager.
 *
 * <p>
 * Cette page ne contient plus que sa <em>configuration</em> : quel statut
 * interroger, et quelles actions proposer. Le chargement, les etats d'ecran et
 * le tableau sont delegates a {@link ValidationsList}, partage avec l'ecran
 * finance — voir ce composant pour les choix de conception.
 * </p>
 *
 * <p>
 * <strong>Les actions sont construites une seule fois</strong> ({@code useMemo}).
 * Elles sont lues pendant le rendu du tableau, jamais dans un effet : des
 * references neuves a chaque rendu ne provoqueraient donc aucun bug, mais
 * signaleraient une liste de dependances inutile a quiconque=y ajouterait un
 * jour un {@code useEffect}.
 * </p>
 *
 * @returns {React.ReactNode} file de validation manager
 */
function ValidationsPage() {
  const actions = useMemo(
    () => [
      { label: 'Approuver', variant: 'primary', handler: approve, icone: CheckCircle2 },
      {
        label: 'Rejeter',
        variant: 'danger',
        requiresComment: true,
        handler: reject,
        icone: XCircle
      }
    ],
    []
  )

  return (
    <ValidationsList
      statut="PENDING_MANAGER"
      title="Validations en attente"
      subtitle="Notes soumises par vos collaborateurs, en attente de votre validation."
      emptyMessage="Toutes les notes de vos collaborateurs ont été traitées."
      actions={actions}
    />
  )
}

export default ValidationsPage