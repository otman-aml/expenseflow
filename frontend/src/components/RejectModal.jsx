import { useEffect, useRef, useState } from 'react'

import Spinner from './Spinner'

/**
 * Boite de dialogue de rejection, avec saisie obligatoire d'un motif.
 *
 * <p>
 * <strong>Pourquoi une modale et non un {@code prompt()}</strong> : la saisie
 * d'un motif est une decision qui engage le workflow — l'employe la lira pour
 * corriger sa note. Un {@code window.prompt} n'accepte pas le style du projet,
 * n'est pas traduisible, et se ferme sur toute touche hors du champ. La modale
 * impose la presence du motif et rend la raison visible.
 * </p>
 *
 * <p>
 * <strong>Le motif est obligatoire des l'affichage.</strong> Le serveur
 * valide {@code @NotBlank}, qui refuse aussi une chaine d'espaces. Bouton
 * desactive tant que le texte n'est pas significatif : l'utilisateur ne peut
 * donc pas produire un 400 avoidable, et n'apprend pas l'existence de la regle
 * par un echec.
 * </p>
 *
 * <p>
 * <strong>Fermeture par la touche Echap</strong> et clic sur le voile, en
 * concordance avec les dialogues natifs. Le clic sur le voile est annule
 * lorsqu'un ordre en cours s'execute : sans cette garde, un double clic
 * enverrait deux rejets.
 * </p>
 *
 * @param {{ isOpen: boolean, title?: string, onClose: () => void, onConfirm: (commentaire: string) => void, busy?: boolean }} props
 * @returns {React.ReactNode} modale, ou rien si fermee
 */
function RejectModal({ isOpen, title = 'Rejeter la note de frais', onClose, onConfirm, busy = false }) {
  const [commentaire, setCommentaire] = useState('')
  const champRef = useRef(null)

  // Le champ est vide a chaque ouverture : un motif de rejet precedent ne doit
  // pas pouvoir etre resoumis par megarde pour une autre note.
  useEffect(() => {
    if (isOpen) {
      setCommentaire('')
      champRef.current?.focus()
    }
  }, [isOpen])

  // Echap ferme, sauf pendant un traitement : interrompre un rejet deja envoye
  // laisserait l'utilisateur dans un etat impossible a comprendre.
  useEffect(() => {
    if (!isOpen || busy) {
      return undefined
    }

    function onKeyDown(evenement) {
      if (evenement.key === 'Escape') {
        onClose()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [isOpen, busy, onClose])

  if (!isOpen) {
    return null
  }

  // trim() : le serveur rejette une chaine de spaces, la valider ici evite
  // d'envoyer une requete qui ne peut aboutir qu'a un 400.
  const valide = commentaire.trim().length > 0

  function handleConfirm() {
    if (valide && !busy) {
      onConfirm(commentaire.trim())
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      // Le voile ferme au clic, mais jamais pendant un traitement en cours.
      onClick={() => !busy && onClose()}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="reject-modal-title"
        // stopPropagation : un clic dans la boite ne doit pas fermer le
        // dialogue, alors qu'un clic sur le voile doit le faire.
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-lg rounded-xl bg-white p-6 shadow-xl"
      >
        <h2 id="reject-modal-title" className="text-lg font-semibold text-slate-900">
          {title}
        </h2>

        <p className="mt-2 text-sm text-slate-600">
          Le motif sera transmis à l&apos;employé, qui pourra corriger sa note en conséquence.
        </p>

        <label className="mt-4 block text-sm font-medium text-slate-700" htmlFor="reject-commentaire">
          Motif du rejet
        </label>
        <textarea
          id="reject-commentaire"
          ref={champRef}
          value={commentaire}
          onChange={(e) => setCommentaire(e.target.value)}
          rows={4}
          maxLength={500}
          disabled={busy}
          placeholder="Ex. : le justificatif manquant pour le trajet du 12 septembre."
          className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-800 focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-100 disabled:bg-slate-50"
        />

        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-slate-100 disabled:opacity-50"
          >
            Annuler
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={!valide || busy}
            className="inline-flex items-center gap-2 rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-200 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy && <Spinner size="sm" />}
            Rejeter
          </button>
        </div>
      </div>
    </div>
  )
}

export default RejectModal