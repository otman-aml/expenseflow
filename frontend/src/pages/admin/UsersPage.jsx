import { useCallback, useEffect, useState } from 'react'
import { PlusCircle } from 'lucide-react'

import { createUser, deleteUser, listManagers, listUsers, updateUser } from '../../api/admin'
import ErrorBanner from '../../components/ErrorBanner'
import Spinner from '../../components/Spinner'
import UserForm from '../../components/UserForm'
import UserTable from '../../components/UserTable'
import { getErrorMessage } from '../../utils/errors'

/**
 * Administration des comptes : liste, création, édition, suppression.
 *
 * <p>
 * <strong>Le formulaire est en ligne, pas en modale.</strong> Un écran
 * d'administration est consulté autant qu'édité : ouvrir une modale
 * masquerait le tableau au moment où l'on veut comparer — « ce compte
 * existe-t-il déjà ? ». La zone de saisie s'insère au-dessus de la liste,
 * qui reste visible pendant toute l'édition.
 * </p>
 *
 * <p>
 * <strong>Les rôles sont les quatre de l'enum backend.</strong> Le front
 * n'invente aucune autorité : après chaque mutation, la liste est
 * rechargée depuis le serveur, seule source de vérité (un compte créé
 * entre-temps apparaît, un rôle modifié par un autre admin est rechargé).
 * </p>
 *
 * @returns {React.ReactNode} page d'administration
 */
function UsersPage() {
  const [comptes, setComptes] = useState([])
  const [managers, setManagers] = useState([])
  const [erreur, setErreur] = useState('')
  const [chargement, setChargement] = useState(true)
  const [enCours, setEnCours] = useState(false)
  const [formOuvert, setFormOuvert] = useState(false)
  const [compteEnEdition, setCompteEnEdition] = useState(null)
  const [idEnSuppression, setIdEnSuppression] = useState(null)

  /**
   * Recharge comptes et managers depuis le serveur.
   *
   * <p>
   * Erreurs avalées dans le polling des managers, mais affichées ici :
   * cette page n'existe que pour l'API d'administration, un échec doit
   * donc se voir. La liste précédente est conservée.
   * </p>
   */
  const recharger = useCallback(async () => {
    setChargement(true)
    setErreur('')
    try {
      const [listeComptes, listeManagers] = await Promise.all([listUsers(), listManagers()])
      setComptes(listeComptes)
      setManagers(listeManagers)
    } catch (e) {
      setErreur(getErrorMessage(e))
    } finally {
      setChargement(false)
    }
  }, [])

  useEffect(() => {
    recharger()
  }, [recharger])

  /**
   * Soumet la création ou l'édition puis recharge la liste.
   *
   * @param {object} payload corps déjà converti par le formulaire
   */
  async function surSoumission(payload) {
    setEnCours(true)
    setErreur('')
    try {
      if (compteEnEdition) {
        await updateUser(compteEnEdition.id, payload)
      } else {
        await createUser(payload)
      }
      setFormOuvert(false)
      setCompteEnEdition(null)
      await recharger()
    } catch (e) {
      // Le formulaire reste ouvert avec les saisies : corriger sans
      // retaper est la moindre des choses après un 409 (e-mail pris).
      setErreur(getErrorMessage(e))
    } finally {
      setEnCours(false)
    }
  }

  /**
   * Ouvre le formulaire en mode édition sur un compte.
   *
   * @param {object} compte compte cliqué
   */
  function surEdition(compte) {
    setErreur('')
    setCompteEnEdition(compte)
    setFormOuvert(true)
  }

  /**
   * Supprime un compte après confirmation explicite.
   *
   * <p>
   * {@code window.confirm} plutôt qu'une modale maison : la suppression
   * d'un compte est rare et linéaire, et le même motif est déjà utilisé
   * pour les transitions de note (RequestActions). Une modale dédiée se
   * justifierait s'il fallait saisir un motif.
   * </p>
   *
   * @param {object} compte compte à supprimer
   */
  async function surSuppression(compte) {
    const accepte = window.confirm(`Supprimer le compte « ${compte.nom} » (${compte.email}) ?`)
    if (!accepte) {
      return
    }

    setErreur('')
    setIdEnSuppression(compte.id)
    try {
      await deleteUser(compte.id)
      await recharger()
    } catch (e) {
      // 409 (compte avec notes, dernier administrateur) et 403
      // (auto-suppression ou rôle révoqué) arrivent ici : le message du
      // serveur est servi tel quel (erreur métier explicite) ou traduit
      // (« Access denied ») — le bandeau d'erreur est le canal unique.
      setErreur(getErrorMessage(e))
    } finally {
      setIdEnSuppression(null)
    }
  }

  /**
   * Referme le formulaire sans mutation.
   */
  function surAnnulation() {
    setFormOuvert(false)
    setCompteEnEdition(null)
    setErreur('')
  }

  return (
    <div>
      {/* --- En-tête de page --- */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Administration</h1>
          <p className="mt-1 text-sm text-slate-500">
            Comptes utilisateurs, rôles et rattachement hiérarchique.
          </p>
        </div>
        {!formOuvert && (
          <button
            type="button"
            onClick={() => {
              setErreur('')
              setCompteEnEdition(null)
              setFormOuvert(true)
            }}
            className="inline-flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-brand-700"
          >
            <PlusCircle size={17} aria-hidden="true" />
            Nouveau compte
          </button>
        )}
      </div>

      {/* --- Bandeau d'erreur : création, édition, suppression, chargement --- */}
      {erreur && <div className="mt-4"><ErrorBanner message={erreur} /></div>}

      {/* --- Formulaire en ligne --- */}
      {formOuvert && (
        <div className="mt-4">
          <UserForm
            compte={compteEnEdition}
            managers={managers}
            soumission={surSoumission}
            annulation={surAnnulation}
            enCours={enCours}
          />
        </div>
      )}

      {/* --- Liste --- */}
      <div className="mt-4">
        {chargement ? (
          <div className="flex items-center justify-center py-16" role="status" aria-label="Chargement">
            <Spinner />
          </div>
        ) : (
          <UserTable
            comptes={comptes}
            edition={surEdition}
            suppression={surSuppression}
            idCourant={compteEnEdition?.id ?? null}
            enCours={idEnSuppression}
          />
        )}
      </div>
    </div>
  )
}

export default UsersPage
