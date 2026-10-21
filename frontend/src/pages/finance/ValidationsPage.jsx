import { useMemo } from 'react'
import { DollarSign, XCircle } from 'lucide-react'

import { reimburse, rejectFinance } from '../../api/expenses'
import ValidationsList from '../../components/ValidationsList'

/**
 * File des notes en attente de remboursement.
 *
 * <p>
 * Symetrique de l'ecran manager, et construit sur le meme composant : seul le
 * statut interroge ({@code PENDING_FINANCE}) et les deux actions changent. La
 * difference tient au vocabulaire — « Rembourser » et non « Approuver » — car
 * le geste n'est pas une validation mais l'execution du paiement.
 * </p>
 *
 * @returns {React.ReactNode} file de remboursement
 */
function ValidationsPage() {
  const actions = useMemo(
    () => [
      { label: 'Rembourser', variant: 'primary', handler: reimburse, icone: DollarSign },
      {
        label: 'Rejeter',
        variant: 'danger',
        requiresComment: true,
        handler: rejectFinance,
        icone: XCircle
      }
    ],
    []
  )

  return (
    <ValidationsList
      statut="PENDING_FINANCE"
      title="Remboursements en attente"
      subtitle="Notes validées par votre manager, en attente de remboursement."
      emptyMessage="Aucune note en attente de remboursement."
      actions={actions}
    />
  )
}

export default ValidationsPage