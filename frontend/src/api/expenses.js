import client from './client'

/**
 * Appels REST du domaine notes de frais.
 *
 * <p>
 * Meme principe que {@code auth.js} : ce module ne traduit que l'URL et la
 * methode. Les regles d'affichage (formatage, traductions de statut) restent
 * dans les composants.
 * </p>
 */

/**
 * Liste les notes de frais de l'utilisateur connecte.
 *
 * <p>
 * L'endpoint ne prend aucun parametre de proprietaire : le backend deduit
 * l'auteur du jeton ({@code findMyRequests(userDetails.getUser())}). C'est
 * volontaire — envoyer un identifiant d'utilisateur permettrait de lire les notes
 * d'autrui, ce que le serveur refuse de toute facon.
 * </p>
 *
 * @returns {Promise<import('./expenses').ExpenseRequestResponse[]>} notes, de la plus recente a la plus ancienne
 * @throws {import('axios').AxiosError} 401 si le jeton a expire
 */
export async function listMyExpenses() {
  const { data } = await client.get('/expense-requests')
  return data
}

/**
 * Cree une note de frais.
 *
 * <p>
 * <strong>Le payload ne contient que les lignes.</strong> Trois champs sont
 * volontairement absents du corps et jamais calcules par le front :
 * </p>
 *
 * <ul>
 *   <li>{@code employeeId} : l'auteur est deduit du jeton. L'envoyer
 *       permettrait de creer une note au nom d'autrui ;</li>
 *   <li>{@code statut} : force a {@code DRAFT} par le service. Les transitions
 *       relevent de la machine a etats, pas du client ;</li>
 *   <li>{@code totalMontant} : c'est la somme des lignes. Le laisser
 *       saisissable rendrait le total affiche incoherent avec le detail.</li>
 * </ul>
 *
 * @param {{ lignes: Array<{ categorieId: number, montant: number, dateDepense: string, description?: string }> }} note
 *        lignes de la note ; au moins une
 * @returns {Promise<import('./expenses').ExpenseRequestResponse>} note creee, statut DRAFT
 * @throws {import('axios').AxiosError} 400 si une categorie est inconnue, 403 acces refuse
 */
export async function createExpense(note) {
  const { data } = await client.post('/expense-requests', note)
  return data
}

/**
 * Recupere une note de frais par son identifiant.
 *
 * <p>
 * Le controle de propriete est applique par le backend : un EMPLOYEE qui
 * demande la note d'un autre recoit un 403, un identifiant inexistant un 404.
 * Le front n'a donc pas a verifier ces cas.
 * </p>
 *
 * @param {number|string} id identifiant de la note
 * @returns {Promise<import('./expenses').ExpenseRequestResponse>} note avec ses lignes
 * @throws {import('axios').AxiosError} 403 acces refuse, 404 note inconnue
 */
export async function getExpenseById(id) {
  const { data } = await client.get(`/expense-requests/${id}`)
  return data
}

/**
 * Liste les notes d'un statut, pour la file de validation d'un validateur.
 *
 * <p>
 * Le meme endpoint est utilise dans deux intention tres differentes : sans
 * {@code statut} il rend les notes de l'appelant, avec {@code statut} il rend la
 * file de travail d'un role de validation. La distinction est portee par le
 * serveur, qui refuse un statut exterieur aux droits de l'appelant par un 403 —
 * l'interface n'a donc pas a reproduire cette regle.
 * </p>
 *
 * @param {string} statut statut a filtrer, par exemple {@code 'PENDING_MANAGER'}
 * @returns {Promise<import('./expenses').ExpenseRequestResponse[]>} notes du statut
 * @throws {import('axios').AxiosError} 403 role non valideur, 400 statut inconnu
 *
 * @example
 * const enAttente = await listByStatut('PENDING_MANAGER')
 */
export async function listByStatut(statut) {
  const { data } = await client.get('/expense-requests', { params: { statut } })
  return data
}

/**
 * Approuve une note au niveau manager.
 *
 * <p>
 * La reponse est volontairement minimale ({@code TransitionResponse}) : le
 * detail est deja charge dans la liste du front, et le chemin apres
 * approbation depend du role appelant — {@code PENDING_FINANCE} pour un manager,
 * {@code REIMBURSED} pour la finance qui valide directement. L'appelant
 * recharge donc la liste plutot que d'appliquer une regle en dur ici.
 * </p>
 *
 * @param {number|string} id identifiant de la note
 * @returns {Promise<import('./expenses').TransitionResponse>} etat atteint
 * @throws {import('axios').AxiosError} 403 role insuffisant, 409 etat incompatible
 */
export async function approve(id) {
  const { data } = await client.post(`/expense-requests/${id}/approve`)
  return data
}

/**
 * Rejette une note au niveau manager, avec un motif obligatoire.
 *
 * <p>
 * Le commentaire n'est pas optionnel cote serveur ({@code @NotBlank}) : une
 * validation {@code 400} ici proviendrait d'un texte vide ou uniquement
 * composé d'espaces. Le composant {@code RejectModal} previent ce cas en
 * invalidant le bouton tant que rien n'est saisi.
 * </p>
 *
 * @param {number|string} id          identifiant de la note
 * @param {string} commentaire        motif du rejet
 * @returns {Promise<import('./expenses').TransitionResponse>} etat atteint
 * @throws {import('axios').AxiosError} 400 motif vide, 403 role insuffisant
 */
export async function reject(id, commentaire) {
  const { data } = await client.post(`/expense-requests/${id}/reject`, { commentaire })
  return data
}

/**
 * Marque une note comme remboursee.
 *
 * <p>
 * Cette action clot definitivement le circuit : la note devient
 * {@code REIMBURSED} et ne peut plus etre modifiee. Elle est reservee au role
 * FINANCE_ADMIN, qui repond 403 aux autres — le frontend se contente de
 * refleter cette regle en n'affichant le bouton qu'a la finance.
 * </p>
 *
 * @param {number|string} id identifiant de la note
 * @returns {Promise<import('./expenses').TransitionResponse>} etat atteint
 * @throws {import('axios').AxiosError} 403 role insuffisant, 409 etat incompatible
 */
export async function reimburse(id) {
  const { data } = await client.post(`/expense-requests/${id}/reimburse`)
  return data
}

/**
 * Rejette une note au niveau finance, avec un motif obligatoire.
 *
 * <p>
 * Endpoint distinct de {@link reject} : les deux aboutissent a un rejet
 * irrevocable, mais a des etats differents ({@code REJECTED_MANAGER} contre
 * {@code REJECTED_FINANCE}) et ne sont ouverts qu'a des roles differents.
 * Confondre les deux reviendrait a laisser un manager rejeter au niveau finance.
 * </p>
 *
 * @param {number|string} id   identifiant de la note
 * @param {string} commentaire motif du rejet
 * @returns {Promise<import('./expenses').TransitionResponse>} etat atteint
 * @throws {import('axios').AxiosError} 400 motif vide, 403 role insuffisant
 */
export async function rejectFinance(id, commentaire) {
  const { data } = await client.post(`/expense-requests/${id}/reject-finance`, { commentaire })
  return data
}

/**
 * Soumet une note au circuit d'approbation.
 *
 * <p>
 * Reservee au proprietaire de la note. Apres l'appel, le statut
 * passe a {@code PENDING_MANAGER}. Le serveur enchaine lui-meme les
 * transitions et decide de l'etat atteint : l'appelant n'a qu'a
 * recharger la note.
 * </p>
 *
 * @param {number|string} id identifiant de la note
 * @returns {Promise<import('./expenses').TransitionResponse>} etat atteint
 * @throws {import('axios').AxiosError} 403 tier, 409 etat incompatible, 409 plafond
 */
export async function submit(id) {
  const { data } = await client.post(`/expense-requests/${id}/submit`)
  return data
}

/**
 * Modifie le contenu d'une note de frais en brouillon.
 *
 * <p>
 * Le corps reprend exactement celui de la creation : seules les lignes sont
 * attendues. L'identifiant, le statut et le total restent determines par le
 * serveur.
 * </p>
 *
 * <p>
 * <strong>Le PUT remplace l'integralite des lignes</strong> : les lignes absentes
 * du corps sont supprimees, et les nouvelles portent de nouveaux identifiants.
 * Aucun ecran ne les expose, ce qui rend l'operation transparente.
 * </p>
 *
 * @param {number|string} id     identifiant de la note
 * @param {{ lignes: Array<{ categorieId: number, montant: number, dateDepense: string, description?: string }> }} payload
 *        nouvelles lignes ; au moins une
 * @returns {Promise<import('./expenses').ExpenseRequestResponse>} note modifiee
 * @throws {import('axios').AxiosError} 403 tier, 404 note inconnue, 409 non-DRAFT
 */
export async function update(id, payload) {
  const { data } = await client.put(`/expense-requests/${id}`, payload)
  return data
}