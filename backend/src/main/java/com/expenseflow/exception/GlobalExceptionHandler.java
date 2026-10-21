package com.expenseflow.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.expenseflow.exception.llm.LlmConfigException;
import com.expenseflow.exception.llm.LlmException;
import com.expenseflow.exception.llm.LlmExtractionException;
import com.expenseflow.exception.llm.LlmTimeoutException;
import com.expenseflow.workflow.BudgetExceededException;
import com.expenseflow.workflow.WorkflowException;

/**
 * Gestionnaire global d'exceptions : uniformise toutes les réponses d'erreur
 * de l'API en JSON.
 *
 * <p>
 * Format de référence, volontairement identique à celui produit par
 * {@code CustomAuthenticationEntryPoint} pour les 401 :
 * </p>
 *
 * <pre>
 * {"error":"Unauthorized","message":"Authentication required"}
 * </pre>
 *
 * <p>
 * Cas particulier de la validation Jakarta
 * ({@link MethodArgumentNotValidException}), où l'on ajoute le détail par champ :
 * </p>
 *
 * <pre>
 * {"error":"Validation failed","fields":{"email":"doit être une adresse e-mail valide"}}
 * </pre>
 *
 * <p>
 * Règle de sécurité : les erreurs 500 ne sont <strong>jamais</strong> renvoyées au
 * client (pas de trace applicative, pas de message SQL, pas de nom de classe).
 * Elles sont journalisées côté serveur et le client reçoit un message générique.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Corps d'erreur simple, aligné sur le format de
     * {@code CustomAuthenticationEntryPoint}.
     *
     * @param error   libellé court et stable de l'erreur
     * @param message description exploitable par le front
     */
    public record ErrorResponse(String error, String message) {
    }

    /**
     * Corps d'erreur de validation : ajoute le détail par champ fautif.
     *
     * @param error  toujours {@code "Validation failed"}
     * @param fields message de violation associé au nom du champ
     */
    public record ValidationErrorResponse(String error, Map<String, String> fields) {
    }

    /**
     * 400 — violations des contraintes Jakarta sur un corps de requête.
     *
     * @param ex exception levée par Spring MVC
     * @return réponse 400 avec le détail par champ
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fieldError -> fields.put(fieldError.getField(), fieldError.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(globalError -> fields.put(globalError.getObjectName(), globalError.getDefaultMessage()));

        log.debug("Validation refusee sur {} champ(s) : {}", fields.size(), fields.keySet());

        return ResponseEntity.badRequest().body(new ValidationErrorResponse("Validation failed", fields));
    }

    /**
     * 400 — valeur de paramètre de requête impossible à convertir.
     *
     * <p>
     * Levée par Spring MVC lorsqu'un {@code @RequestParam} typé reçoit une
     * valeur qui n'appartient pas à l'énumération visée, par exemple
     * {@code ?statut=INVALIDE}. Sans ce gestionnaire, l'exception remonterait au
     * gestionnaire générique et produirait un <strong>500</strong> — une faute de
     * saisie transformée en panne du serveur, alors qu'elle relève du client.
     * </p>
     *
     * <p>
     * Le nom de la valeur fautive est repris dans le message : le caller voit
     * ainsi ce qu'il a envoyé, pas seulement ce qu'il aurait pu envoyer.
     * </p>
     *
     * @param ex exception levée par Spring MVC
     * @return réponse 400 nommant le paramètre fautif
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Valeur invalide pour le parametre '" + ex.getName() + "' : " + ex.getValue();

        log.debug("Conversion impossible : {}", message);

        return ResponseEntity.badRequest().body(new ErrorResponse("Bad Request", message));
    }

    /**
     * 503 — brique IA inactive faute de clé configurée.
     *
     * <p>
     * Cas particulier, et le seul des erreurs Llm à ne pas être un 5xx
     * générique : l'application fonctionne normalement, seule l'extraction est
     * indisponible. Le message le dit, afin que le front puisse proposer la
     * saisie manuelle plutôt qu'afficher une panne.
     * </p>
     *
     * @param ex exception levée par le client Llm
     * @return réponse 503
     */
    @ExceptionHandler(LlmConfigException.class)
    public ResponseEntity<ErrorResponse> handleLlmConfig(LlmConfigException ex) {
        log.warn("Brique IA indisponible : {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Service indisponible", ex.getMessage()));
    }

    /**
     * 504 — l'appel à Llm a dépassé le délai.
     *
     * <p>
     * Seule erreur traduite par un code figé sans message applicatif : le
     * délai de 15 s est une contrainte connue, l'utilisateur n'a pas à la
     * connaître et ne peut rien corriger.
     * </p>
     *
     * @param ex exception de délai
     * @return réponse 504
     */
    @ExceptionHandler(LlmTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleLlmTimeout(LlmTimeoutException ex) {
        log.warn("Delai du fournisseur depasse : {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ErrorResponse("Gateway Timeout", ex.getMessage()));
    }

    /**
     * 422 — la reponse du modele ne permet pas de pre-remplir le formulaire.
     *
     * <p>
     * <strong>Seule erreur IA traduite par un message detailing.</strong> Les
     * autres (502) dissimulent volontairement leur nature, parce qu'aucune ne
     * demande d'action a l'utilisateur. Ici, dire « categorie inconnue : 99 »
     * ou « date dans le futur » lui indique exactement ce qu'il doit corriger
     * avant de relancer une extraction — ou de saisir la ligne a la main.
     * </p>
     *
     * @param ex exception d'extraction
     * @return réponse 422
     */
    @ExceptionHandler(LlmExtractionException.class)
    public ResponseEntity<ErrorResponse> handleLlmExtraction(LlmExtractionException ex) {
        log.warn("Extraction IA inexploitable : {}", ex.getMessage());

        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("Extraction impossible", ex.getMessage()));
    }

    /**
     * 502 — appel à Llm impossible.
     *
     * <p>
     * Couvre la cle refusee (401/403), le quota atteint (429) et la panne du
     * fournisseur (5xx). Tous se traduisent du point de vue de l'utilisateur :
     * l'extraction n'a pas fonctionne, et la saisie manuelle reste la solution.
     * Le type exact est journalise pour le diagnostic, sans etre expose — un
     * message « cle API refusee » n'apporterait rien a l'utilisateur et
     * confirmerait au contraire qu'un secret est attendu par l'API.
     * </p>
     *
     * @param ex exception Llm de toute autre nature
     * @return réponse 502
     */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ErrorResponse> handleLlm(LlmException ex) {
        log.warn("Appel LLM en echec ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("Bad Gateway",
                        "L'extraction par IA est momentanement indisponible."));
    }

    /**
     * 401 — identifiants invalides (e-mail inconnu ou mot de passe incorrect).
     *
     * <p>
     * Le même message est renvoyé dans les deux cas pour ne pas révéler
     * l'existence (ou non) d'un compte : c'est le principe de mitigation de
     * l'<em>user enumeration</em>.
     * </p>
     *
     * @param ex exception d'authentification
     * @return réponse 401
     */
    @ExceptionHandler({ BadCredentialsException.class, UsernameNotFoundException.class })
    public ResponseEntity<ErrorResponse> handleUnauthorized(RuntimeException ex) {
        log.debug("Echec d'authentification : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Unauthorized", "Invalid email or password"));
    }

    /**
     * 403 — jeton valide mais rôle insuffisant, contrôle évalué par
     * {@code @PreAuthorize}.
     *
     * <p>
     * Ce handler est indispensable en complément de
     * {@code CustomAccessDeniedHandler}. En effet, une annotation
     * {@code @PreAuthorize} est évaluée <em>à l'intérieur</em> du
     * {@code DispatcherServlet} : l'{@code AccessDeniedException} est alors
     * propagée comme une exception MVC classique et capturée ici, avant
     * d'atteindre l'{@code ExceptionTranslationFilter} de Spring Security.
     * Sans ce handler, elle tomberait dans
     * {@link #handleGeneric(Exception)} et la réponse serait un 500 trompeur.
     * </p>
     *
     * <p>
     * {@code CustomAccessDeniedHandler} couvre, lui, le cas symétrique : refus
     * décidé au niveau de la chaîne de filtres (par exemple une autorité
     * d'URL), sans passer par le dispatcher. Les deux produisent exactement le
     * même corps JSON, afin que le front traite 401 et 403 de façon homogène.
     * </p>
     *
     * @param ex exception d'autorisation
     * @return réponse 403 au format JSON
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.debug("Acces refuse : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("Forbidden", "Access denied"));
    }

    /**
     * 404 — ressource inexistante.
     *
     * @param ex exception métier
     * @return réponse 404
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        log.debug("Ressource introuvable : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Not Found", ex.getMessage()));
    }

    /**
     * Erreur serveur : message volontairement générique côté client.
     *
     * @param error   toujours {@code "Internal Server Error"}
     * @param message message générique, sans aucun détail technique
     */
    public record ServerErrorResponse(String error, String message) {
    }

    /**
     * 409 — e-mail déjà utilisé lors d'une inscription.
     *
     * @param ex exception métier
     * @return réponse 409
     */
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleConflict(EmailAlreadyExistsException ex) {
        log.debug("Inscription refusee : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 400 — ancien mot de passe incorrect lors d'un changement.
     *
     * <p>
     * <strong>Pas 401, délibérément.</strong> Un 401 déclencherait la
     * déconnexion automatique du client Axios : l'utilisateur verrait sa
     * session fermée pour une simple faute de frappe, et perdrait le
     * formulaire qu'il était en train de remplir. Or son jeton est valide —
     * c'est la saisie d'un second facteur qui échoue, pas l'authentification.
     * </p>
     *
     * @param ex exception de mot de passe invalide
     * @return réponse 400
     */
    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPassword(InvalidPasswordException ex) {
        log.debug("Ancien mot de passe incorrect : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Bad Request", ex.getMessage()));
    }

    /**
     * 400 — les deux saisies du nouveau mot de passe ne concordent pas.
     *
     * @param ex exception de confirmation non concordante
     * @return réponse 400
     */
    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePasswordMismatch(PasswordMismatchException ex) {
        log.debug("Confirmation de mot de passe incoherente : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Bad Request", ex.getMessage()));
    }

    /**
     * 409 — transition de workflow interdite.
     *
     * <p>
     * Levée par {@code ExpenseWorkflow.assertTransition} lorsqu'une note se
     * trouve dans un état qui ne permet pas l'action demandée : approuver une
     * note déjà remboursée, soumettre une note déjà soumise, etc.
     * </p>
     *
     * <p>
     * <strong>Distincte du 403</strong>, qui signale un défaut de droits. Ici
     * l'appelant avait tous les droits : c'est l'<em>état</em> de la ressource
     * qui s'y oppose. La distinction permet au client d'afficher « cette note
     * a déjà été traitée » plutôt qu'une erreur d'autorisation qui le
     * pousserait à croire qu'il lui manque des droits.
     * </p>
     *
     * @param ex exception de workflow
     * @return réponse 409 avec le message décrivant la transition refusée
     */
    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ErrorResponse> handleWorkflow(WorkflowException ex) {
        log.debug("Transition refusee : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 — note dépassant un ou plusieurs plafonds budgétaires.
     *
     * <p>
     * Levée à la soumission lorsque le montant d'une ligne dépasse le plafond
     * configuré pour le couple (catégorie, rôle). Toutes les violations sont
     * énumérées dans le message : l'employé peut ainsi corriger sa note en une
     * seule passe.
     * </p>
     *
     * <p>
     * Même code que {@link WorkflowException} — le refus est fonctionnel, pas
     * lié aux droits — afin que le client traite les deux de la même façon.
     * </p>
     *
     * @param ex exception de dépassement budgétaire
     * @return réponse 409 listant les dépassements
     */
    @ExceptionHandler(BudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handleBudgetExceeded(BudgetExceededException ex) {
        log.debug("Plafond depasse : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 — catégorie encore référencée par des plafonds ou des notes de frais.
     *
     * @param ex exception métier
     * @return réponse 409
     */
    @ExceptionHandler(CategoryInUseException.class)
    public ResponseEntity<ErrorResponse> handleCategoryInUse(CategoryInUseException ex) {
        log.debug("Suppression de categorie refusee : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 — compte encore propriétaire de notes de frais.
     *
     * <p>
     * Même rationale que {@link #handleCategoryInUse} : la clé étrangère
     * {@code expense_requests.employee_id} refuse la suppression, et
     * traduire ce refus en 409 message lisible évite un 500 trompeur.
     * </p>
     *
     * @param ex exception métier
     * @return réponse 409
     */
    @ExceptionHandler(UserInUseException.class)
    public ResponseEntity<ErrorResponse> handleUserInUse(UserInUseException ex) {
        log.debug("Suppression de compte refusee : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 — suppression du dernier compte ADMIN.
     *
     * <p>
     * L'appelant est habilité (le contrôleur l'a vérifié) mais l'état de la
     * table rend l'opération impossible. Répondre 500 (violations de
     * contrainte) ou 403 (« refusé » sans raison) masquerait la cause réelle :
     * l'utilisateur doit comprendre qu'il faut d'abord promouvoir un autre
     * compte ADMIN.
     * </p>
     *
     * @param ex exception métier
     * @return réponse 409
     */
    @ExceptionHandler(LastAdminException.class)
    public ResponseEntity<ErrorResponse> handleLastAdmin(LastAdminException ex) {
        log.debug("Suppression du dernier admin refusee : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 — compte déjà cité comme auteur dans le journal d'audit.
     *
     * <p>
     * Symétrique de {@link #handleUserInUse} : la clé
     * {@code audit_log.utilisateur_id} refuse la suppression
     * ({@code ON DELETE RESTRICT}). Répondre 409 avec le motif plutôt que
     * subir un 500 de violation de contrainte — et le motif est définitif
     * (la trace ne se retire pas), ce que le message porte.
     * </p>
     *
     * @param ex exception métier
     * @return réponse 409
     */
    @ExceptionHandler(UserHasAuditLogException.class)
    public ResponseEntity<ErrorResponse> handleUserHasAuditLog(UserHasAuditLogException ex) {
        log.debug("Suppression de compte auteur d'audit refusee : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 — ressource en doublon (nom de catégorie ou couple catégorie/rôle déjà
     * occupé).
     *
     * @param ex exception métier
     * @return réponse 409
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex) {
        log.debug("Conflit d'unicite detecte : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", ex.getMessage()));
    }

    /**
     * 409 (secours) — violation d'unicité au niveau base de données.
     *
     * <p>
     * Filet de sécurité pour les courses entre deux requêtes concurrentes, et
     * pour le cas où le contrôle applicatif passe alors que la contrainte SQL
     * ne l'est pas encore. Ce cas est réel : une entité insérée dans la
     * transaction courante n'est pas visible des requêtes suivantes avant le
     * flush, donc un {@code findByNom} exécuté juste après une création peut
     * ne pas la voir.
     * </p>
     *
     * <p>
     * Le nom de la contrainte PostgreSQL est exploité pour renvoyer un message
     * qui désigne la bonne ressource, plutôt qu'un libellé générique trompeur
     * : la même exception sert aujourd'hui à la fois aux utilisateurs
     * ({@code users_email_key}) et aux catégories ({@code categories_nom_key}).
     * </p>
     *
     * @param ex exception JPA/JDBC
     * @return réponse 409
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        log.warn("Violation d'integrite en base : {}", cause);

        String message = "Ressource en doublon";
        if (cause != null) {
            if (cause.contains("users_email_key")) {
                message = "Email already exists";
            } else if (cause.contains("categories_nom_key")) {
                message = "Une catégorie portant ce nom existe déjà";
            } else if (cause.contains("uk_budget_limits_categorie_role")) {
                message = "Un plafond existe déjà pour cette catégorie et ce rôle";
            }
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", message));
    }

    /**
     * 500 — toute autre exception inattendue.
     *
     * <p>
     * La stack trace complète est journalisée côté serveur ; le client ne reçoit
     * qu'un message générique afin de ne pas exposer de détail technique.
     * </p>
     *
     * @param ex exception inattendue
     * @return réponse 500 générique
     */
    // TODO: intercepter DataAccessException pour distinguer « base indisponible »
    //       de « bug applicatif » et renvoyer un message actionnable (« service
    //       temporairement indisponible, réessayez ») au lieu du 500 générique.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ServerErrorResponse> handleGeneric(Exception ex) {
        log.error("Erreur inattendue non geree", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ServerErrorResponse("Internal Server Error", "An unexpected error occurred"));
    }
}

