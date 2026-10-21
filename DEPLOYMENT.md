# Deploiement d'ExpenseFlow

Ce document decrit la mise en ligne du projet : PostgreSQL managé (Neon),
API (Render) et front (Vercel).

> **Ordre imperatif : Neon, puis Render, puis Vercel.** Chaque etape a besoin
> de l'URL de la precedente. Commencer par Vercel donnerait un front sans API,
> et le premier clic ne fonctionnerait pas.

---

## 0. Lancement local en un geste

Avant tout deploiement, la pile se lance entierement en local :

```bash
cp .env.example .env    # puis renseigner les valeurs
docker compose up -d --build
```

Trois services demarrent : PostgreSQL (5434), API (8090), front (4300).
Le premier build est long (telechargement des images de base et compilation
Maven) ; les suivants sont bien plus rapides grace au cache Docker.

### Le fichier `.env` est obligatoire

`docker compose up` **refuse de demarrer** si une variable obligatoire est
absente. L'erreur nomme la variable fautive :

```
error while interpolating services.backend.environment.JWT_SECRET:
required variable JWT_SECRET is missing a value
```

C'est un choix delibere. Les secrets ne sont pas ecrits en dur dans
`docker-compose.yml` — ce fichier est **commite**, un secret y voyagerait dans
le depot. Preferez donc un demarrage qui echoue immediatement et nomme la
variable manquante, a une pile qui demarre a moitie configuree et dont le
defaut n'apparaitrait qu'a la premiere connexion.

Les deux variables obligatoires sont :

| Variable | Effet si absente |
|---|---|
| `JWT_SECRET` | `docker compose up` echoue. Sert a signer les jetons. |
| `GROQ_API_KEY` | `docker compose up` echoue. Sans elle, l'extraction IA renvoie 503. |

> `GROQ_API_KEY` est obligatoire **pour le demarrage**, meme si l'application
> demarre sans elle. C'est plus strict que le comportement de l'application,
> qui tolere une saisie manuelle : la coherence de la pile est preferable a
> decouvrir l'absence de la brique IA au moment d'une demonstration.

Le reste des variables (`.env.example`) est facultatif et possede une valeur
par defaut dans le compose : `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`.

> **Ne jamais commiter `.env`.** Il est deja liste dans `.gitignore`. Seul
> `.env.example`, qui ne contient aucune valeur reelle, est versionne.

---

## 1. Base de donnees — Neon

1. Creer un compte sur <https://neon.tech> (palier gratuit).
2. **New Project** > nom `expenseflow`, region la plus proche de l'utilisateur.
3. Le tableau de bord fournit une chaine de connexion de la forme :
   ```
   postgresql://USER:PASSWORD@ep-xxx.region.aws.neon.tech/expenseflow?sslmode=require
   ```
4. **Ne pas utiliser l'URL "pooled"** pour Flyway si elle limite le nombre de
   connexions ; l'URL directe suffit pour une instance gratuite.

Les migrations Flyway s'appliquent **automatiquement au demarrage** de l'API
(`spring.flyway.enabled=true`). Aucune commande manuelle n'est necessaire.

### Variable a extraire

| Element | Nom de la variable |
|---|---|
| URL de connexion | `SPRING_DATASOURCE_URL` |
| Utilisateur | `SPRING_DATASOURCE_USERNAME` |
| Mot de passe | `SPRING_DATASOURCE_PASSWORD` |

---

## 2. API — Render

1. Creer un compte sur <https://render.com> (palier gratuit).
2. **New > Web Service**, puis connecter le depot GitHub.
3. Reglages :
   - **Root Directory** : `backend`
   - **Runtime** : Docker (le `Dockerfile` fourni compile et execute l'API)
   - **Region** : meme region que Neon
   - **Health Check Path** : `/actuator/health`
4. Dans **Environment**, declarer les variables ci-dessous.
5. Deployer. La premiere URL aura la forme
   `https://expenseflow-api.onrender.com`.

### Variables d'environnement

Le fichier `.env.example` à la racine du dépôt documente les variables
attendues **en développement local**, avec leur rôle. Les noms diffèrent en
production : les services managés (Render, Neon) fournissent les leurs.

```
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://USER:PASSWORD@HOST/expenseflow?sslmode=require
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
JWT_SECRET=<64 caracteres hexadecimaux, tires au hasard>
GROQ_API_KEY=<cle Groq, facon gsk_...>
CORS_ALLOWED_ORIGINS=<URL exacte du front Vercel>
```

#### Comment generer `JWT_SECRET`

Ouvrir PowerShell et executer :

```powershell
-join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })
```

Ou, sous Linux/macOS : `openssl rand -hex 32`.

> **Ne jamais commiter cette valeur.** Elle permet de fabriquer un jeton pour
> n'importe quel compte. Si elle fuite, il suffit de la changer et de
> redployer.

#### Contrainte sur `CORS_ALLOWED_ORIGINS`

La valeur doit etre **exactement** l'origine du front : `https` et le nom de
domaine, **sans barre finale oblique**.

```
correct   : https://expenseflow.vercel.app
incorrect : https://expenseflow.vercel.app/
incorrect : *
```

Une origine erronee ne produit aucune erreur cote serveur : le navigateur
refuse l'appel et la seule trace est dans la console du front. C'est la
principale cause de panne d'un deploiement de ce type.

Plusieurs origines sont acceptees, separees par des virgules et sans espaces
superflus :

```
CORS_ALLOWED_ORIGINS=https://expenseflow.vercel.app,https://expenseflow-git-feat.vercel.app
```

---

## 3. Front — Vercel

1. Creer un compte sur <https://vercel.com> (palier gratuit).
2. **Add New > Project**, importer le meme depot.
3. Reglages :
   - **Root Directory** : `frontend`
   - **Framework Preset** : Vite
   - **Build Command** : `npm run build`
   - **Output Directory** : `dist`
4. Declarer la variable d'environnement :
   ```
   VITE_API_URL=https://expenseflow-api.onrender.com
   ```
5. Deployer.

> **Le prefixe `VITE_` est obligatoire** : Vite n'injecte dans le bundle que
> les variables qui le portent. Une variable nommee `API_URL` disparaitrait a
> la compilation, et le front appellerait une URL relative — donc le domaine
> Vercel lui-meme, qui n'expose aucune API. Le symptome serait une page
> blanche en console, sans le moindre message exploitable.

> **Ne pas mettre de barre finale oblique.** `VITE_API_URL` doit se terminer
> par le nom de domaine : c'est `client.js` qui ajoute `/api`. Une valeur
> terminee par `/` produirait `https://.../api` suivi d'un `/api` de trop.

---

## 4. Ordre de mise a jour quand l'URL change

Si l'URL du front change (ce qui arrive a chaque redeloiement d'un service
Vercel derriere une adresse temporaire), la variable `CORS_ALLOWED_ORIGINS`
cote Render doit suivre. Concretement :

1. Deployer le front, recuperer sa nouvelle URL.
2. Mettre a jour `CORS_ALLOWED_ORIGINS` sur Render.
3. Redployer ou redemarrer l'API Render.

L'ordre inverse ne suffit pas : l'API ne connaitrait pas encore la nouvelle
origine.

### Le piege du premier deploiement

Au tout premier deploiement, l'URL du front **n'existe pas encore** quand on
configure `CORS_ALLOWED_ORIGINS` cote Render. Deux solutions :

- **La plus simple** : deploying le front d'abord, sur une URL temporaire,
  puis mettre a jour la variable avant d'ouvrir la demonstration.
- **Si l'URL de production est deja fixee** (par exemple un domaine
  personnalise), la declarer des le depart : aucune mise a jour ulterieure
  n'est necessaire.

Ne pas deploying l'API avec une origine previsionnelle (`*`) « pour tester » :
`allowCredentials(true)` rend cette valeur illegale, et l'application
refuse de demarrer.

---

## 5. Verifications apres deploiement

```bash
# 1. L'API repond (le /actuator/health est public)
curl https://expenseflow-api.onrender.com/actuator/health

# 2. Swagger est accessible
#    https://expenseflow-api.onrender.com/swagger-ui/index.html

# 3. L'authentification fonctionne : 200
curl -X POST https://expenseflow-api.onrender.com/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"...","motDePasse":"..."}'

# 4. Une requete sans jeton est refusee : 401
curl -i https://expenseflow-api.onrender.com/api/ai/stats
```

Puis, dans le navigateur : ouvrir le front, se connecter, et verifier que la
console ne contient **aucune erreur CORS**. C'est le test qui distingue un
deploiement reussi d'un deploiement qui "demarre mais ne fait rien".

---

## 6. Limites des paliers gratuits

Ces offres ont des contraintes a connaitre avant de promettre une demonstration :

| Service | Limite |
|---|---|
| Render (free) | extinction apres ~15 min d'inactivite ; le premier appel suivant prend ~50 s |
| Vercel (hobby) | 100 deploiements par jour, pas de deploiement en previsualisation sur demande |
| Neon (free) | 0,5 Go de stockage, extinction du projet apres 5 jours d'inactivite |

**Consequence pour une demonstration en ligne :** ouvrir l'URL et attendre une
minute avant d'interagir. Ce delai est normal, non un bug.
