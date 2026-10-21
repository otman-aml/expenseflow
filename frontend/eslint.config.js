import js from '@eslint/js'
import globals from 'globals'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import unusedImports from 'eslint-plugin-unused-imports'

/**
 * Configuration ESLint du frontend (flat config, ESLint 9).
 *
 * <p>
 * <strong>Volontairement minimale.</strong> Elle ne verifie que deux familles
 * de defauts : le code mort (imports et variables inutilises) et les regles
 * des hooks React, dont la violation provoque des erreurs a l'execution.
 * Ajouter des regles de style — guillemets, indentation, point-virgule —
 * n'apporterait rien ici : le projet n'a pas de convention de formatage
 * documentee, et une telle regle produirait des centaines d'avertissements
 * sans ameliorer le code.
 * </p>
 *
 * <p>
 * <strong>Une seule regle de {@code eslint-plugin-react} est activee :
 * {@code react/jsx-uses-vars}.</strong> Elle marque comme utilisees les
 * variables employees seulement dans du JSX, ce que les regles standard ne
 * peuvent pas voir. Sans elle, 76 avertissements signalaient a tort des
 * composants employes : les supprimer aurait casse le rendu. Les autres
 * regles du plugin — toutes des conventions de style — restent inactives, et
 * {@code recommended} n'est volontairement pas importe : l'appliquer
 * produirait des centaines d'avertissements sans valeur.
 * </p>
 *
 * <p>
 * <strong>ESLint 9 et non 10 :</strong> {@code eslint-plugin-react} exige
 * ESLint 9. C'est la seule contrainte de version du projet, et elle est
 * entierement portee par une regle.
 * </p>
 */
export default [
    {
        ignores: ['dist/**', 'node_modules/**']
    },

    js.configs.recommended,

    {
        files: ['**/*.{js,jsx}'],

        languageOptions: {
            ecmaVersion: 2022,
            sourceType: 'module',
            parserOptions: {
                ecmaFeatures: { jsx: true }
            },
            globals: {
                ...globals.browser
            }
        },

        plugins: {
            react,
            'react-hooks': reactHooks,
            'unused-imports': unusedImports
        },

        settings: {
            react: { version: 'detect' }
        },

        rules: {
            // --- Code mort ---
            // `unused-imports` complete `no-unused-vars` : il couvre les
            // imports, que la regle standard ignore car ils ont un effet de
            // bord (chargement du module). Sans lui, un import oublie
            // passerait inapercu.
            'no-unused-vars': 'warn',
            'unused-imports/no-unused-imports': 'warn',
            'unused-imports/no-unused-vars': [
                'warn',
                {
                    // `_` prefixe : convention usuelle pour une variable
                    // volontairement non utilisee. La respecter evite un
                    // avertissement sur un parametre impose par une API.
                    argsIgnorePattern: '^_',
                    varsIgnorePattern: '^_'
                }
            ],

            // --- JSX ---
            // SEULE regle de eslint-plugin-react activee : elle dit au
            // analyseur qu'un identifiant employe dans une balise est
            // reellement utilise.
            'react/jsx-uses-vars': 'error',

            // --- Hooks React ---
            // `rules-of-hooks` est une ERREUR et non un avertissement :
            // appeler un hook hors d'un composant, ou dans une condition,
            // casse le rendu de facon aleatoire. Ce n'est pas une question de
            // style mais de fonctionnement.
            'react-hooks/rules-of-hooks': 'error',
            'react-hooks/exhaustive-deps': 'warn',

            // Une reference non definie est une panne, pas une maladresse :
            // elle se manifeste en production, sur un chemin rarement pris.
            'no-undef': 'error'
        }
    }
]