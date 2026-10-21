# Intégration continue

Ce dossier contient le pipeline de vérification d'ExpenseFlow.

> **Le pipeline ne déploie rien.** Il vérifie que le code compile, que les
> tests passent et que le front se construit. La mise en ligne reste manuelle
> (voir [`../../DEPLOYMENT.md`](../../DEPLOYMENT.md)).

---

## `ci.yml` — ce qui est exécuté

| Job | Vérifie | Commande |
|---|---|---|
| **Backend** | Compilation + suite complète | `./mvnw -B clean test` |
| **Frontend** | Qualité de code | `npm run lint` |
| **Frontend** | Build de production | `npm run build` |

Les deux jobs sont **indépendants** : un échec du front n'empêche pas de lire
le résultat du backend.

### Déclenchement

- `push` sur `main`
- `pull_request` visant `main`

Aucun déploiement automatique : ni `deploy`, ni `release`, ni `publish`.

---

## Deux choix qui ne sont pas arbitraires

### Node 22, pas Node 20

Vite 8 déclare dans son `package.json` :

```json
"engines": { "node": "^20.19.0 || >=22.12.0" }
```

Node 20 n'est donc acceptable qu'à partir de la **20.19**. Écrire
`node-version: '20'` dans le workflow laisse `setup-node` installer une
version20.x arbitraire : le build échouerait sur une 20.18, et passerait sur
une 20.19. Node 22 supprime cette ambiguïté.

### `npm ci`, pas `npm install`

`npm ci` installe exactement ce que `package-lock.json` fixe et **échoue** si
le verrouillage est incohérent avec `package.json`. C'est ce qui garantit
qu'une CI verte correspond à un build reproductible, et non à un build qui
aurait silencieusement résolu de nouvelles versions.

---

## Les tests backend et Docker

Les tests d'intégration lèvent un conteneur PostgreSQL via **Testcontainers**.
C'est possible sur les runners GitHub, qui ont Docker.

C'est exactement la situation inverse du build d'image : le démon Docker est
absent du *buildkit*, ce qui oblige à `-DskipTests` dans
`backend/Dockerfile`. Les deux choix sont liés et se justifient mutuellement.

---

## Vérifier le workflow sans GitHub

Le fichier `.yml` ne fait rien tant que le dépôt n'est pas sur GitHub : aucun
mécanisme ne l'exécute en local. Deux moyen de le contrôler avant le premier
push.

### 1. Le YAML est-il valide ?

```bash
python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('YAML valide')"
```

### 2. Les commandes passent-elles en local ?

C'est la vérification qui compte : elle teste le contenu, pas la
syntaxe. Le workflow ne fait qu'enchaîner ces commandes.

```bash
# Ce que fait le job backend
cd backend && ./mvnw -B clean test

# Ce que fait le job frontend
cd frontend
npm ci
npm run lint
npm run build
```

Sur Windows, `mvnw` s'appelle `mvnw.cmd` ; le workflow utilise `./mvnw` car
le runner est Linux.

### 3. `act` — exécuter le workflow localement

[`act`](https://github.com/nektos/act) fait tourner un workflow GitHub
Actions sur sa machine :

```bash
act                          # exécute ce workflow
act -j backend               # un seul job
act -P ubuntu-latest=cimg/node:22.0
```

Prévoir une image de runner à télécharger au premier lancement (plusieurs
gigaoctets). `act` ne reproduit pas GitHub à l'identique : un workflow vert en
local peut échouer sur GitHub, et l'inverse est possible. C'est un bon
contrôle de première passe, pas une garantie.

---

## Diagnostic des échecs

| Symptôme | Cause probable |
|---|---|
| `Could not find a valid Docker environment` | Docker indisponible : les tests Testcontainers ne peuvent pas lever leur conteneur. |
| Unsupported engine / `EBADENGINE` | Version de Node inférieure à `^20.19.0`. |
| `npm ci` échoue sur le verrouillage | `package.json` modifié sans regénérer `package-lock.json`. |
| Échec `./mvnw : Permission denied` | Bit d'exécution absent : `chmod +x backend/mvnw`. |
| `npm run lint` signale des erreurs | Le code source a une erreur ESLint : le contrôle a fait son travail. |

---

## Voir les résultats

Après le premier push sur GitHub : onglet **Actions** du dépôt. Chaque exécution
affiche le journal complet de chaque étape, et le détail de l'échec est
accessible directement dans la sortie de la commande.