package com.expenseflow.service;

import com.expenseflow.exception.llm.LlmConfigException;
import com.expenseflow.exception.llm.LlmException;

/**
 * Contrat d'appel a un fournisseur de completion de chat.
 *
 * <p>
 * <strong>Pourquoi cette interface existe.</strong> Le projet a commence sur
 * Llm, puis a bascule sur Groq : le premier a ferme son palier gratuit, le
 * second offre l'API dans un format compatible. Sans cette interface, ce
 * changement de fournisseur aurait traverse {@code AiExtractionService},
 * {@code AiController} et les tests — trois fichiers dont aucun ne parle de
 * transport. Avec elle, le fournisseur est un detail d'injection : le service
 * d'extraction ignore quel modele repond, et le journal enregistre le modele
 * reel plutot qu'une chaine recopiee ailleurs.
 * </p>
 *
 * <p>
 * <strong>Le vocabulaire est celui du contrat, pas celui du metier.</strong> On
 * parle de « completion » et non de « depense extraite » : l'implementation
 * doit pouvoir repondre a une question generique, et c'est
 * {@code AiExtractionService} qui lui donne un sens. Inversement, la validation
 * du JSON obtenu n'a rien a faire ici : une reponse peut etre parfaitement
 * conforme au format et inapplicable a une note de depense.
 * </p>
 *
 * <p>
 * <strong>Une seule implementation est active a la fois</strong>, choisie par
 * {@code @Service}. Une seconde implementation (un autre fournisseur) ne
 * s'activerait qu'a l'annoter, ce qui remplacerait celle-ci par une
 * erreur de contexte au demarrage plutot que par un conflit silencieux.
 * </p>
 */
public interface LlmClient {

    /**
     * Demande une completion et renvoie le contenu et les compteurs de tokens.
     *
     * @param prompt consigne envoyee au modele
     * @return reponse complete
     * @throws LlmConfigException si le fournisseur n'est pas configure
     * @throws com.expenseflow.exception.llm.LlmTimeoutException si l'appel depasse le delai
     * @throws LlmException pour tout autre echec d'appel
     */
    LlmResponse chatCompletionAvecUsage(String prompt);

    /**
     * Demande une completion et renvoie le seul contenu produit.
     *
     * @param prompt consigne envoyee au modele
     * @return contenu produit par le modele, tel quel
     * @see #chatCompletionAvecUsage(String)
     */
    String chatCompletion(String prompt);

    /**
     * Identifiant du modele reellement interroge.
     *
     * <p>
     * <strong>Cette valeur alimente la colonne {@code modele} du journal</strong>,
     * qui sert a reconstituer une facture et a comparer des mesures dans le
     * temps. Elle doit donc venir de l'implementation — seule a savoir quel
     * modele elle appelle — et non etre une constante recopiee dans le service
     * d'extraction : deux chaines identiques dans deux fichiers finissent par
     * diverger a la premiere evolution de l'un des deux.
     * </p>
     *
     * @return nom du modele, par exemple {@code openai/gpt-oss-120b}
     */
    String modele();
}
