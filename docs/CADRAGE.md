# GhOst — Notes de cadrage technique (lot L0)

Source de référence : [`GhOst_Cahier_des_Charges_V1.0_2026-11-09.pdf`](GhOst_Cahier_des_Charges_V1.0_2026-11-09.pdf) (ci-après « CDC »).
Ce document trace ce qui est implémenté, les interprétations retenues et les points à arbitrer.
Il ne remplace pas le CDC.

## 1. État d'avancement

| Lot CDC §17 | Contenu | État |
|---|---|---|
| L0 — Conception | Protocole d'expiration, modèle d'états, règles de rappel | **Domaine implémenté et testé** (`core/expiry`) ; maquettes UX, modèle de menace détaillé et modèle de données : à produire |
| L1 — MVP (P0) | Comptes, contacts, chat E2EE, 3 modes, limite absolue, 4 alertes, UI GhOst | Non commencé (nécessite SDK Android, choix de la bibliothèque E2EE et du backend) |
| L2 à L4 | Multimédia, innovation, préproduction | Non commencé |

## 2. Module `core/expiry` — traçabilité

Module Kotlin/JVM pur, sans dépendance Android, réutilisable par l'application et par un éventuel backend JVM.

| Exigence CDC | Implémentation | Tests |
|---|---|---|
| §00, §4.1 Plafond envoi + 24 h | `ExpiryPolicy.absoluteDeadline` | T01, T03, T04 |
| §14.2 Algorithme contractuel | `ExpiryPolicy.expiresAt` | T01–T05, T11 |
| §4.3 Lecture unique, idempotente | `MessageLifecycle.recordFirstRead` | cas limites |
| §14.1 États, EXPIRÉ / PURGÉ terminaux | `MessageState`, `MessageLifecycle` | T08, T10 |
| §14.3 Horloge, non-prolongation | `TrustedClock` | T09 |
| §5.1 Quatre rappels neutres | `ReminderThreshold`, `ReminderPlanner` | T06 |
| §5.2 Annulation, non-rejeu, regroupement, mode discret | `ReminderPlanner`, `ReminderGrouper` | T07, T12, T13, cas limites |
| §13, §19 Rétention relais 24 h max | `RelayRetention` | T14 |

Tests de recette du CDC §18 **non couverts** par ce module (relèvent de l'application ou de l'infrastructure) :
purge physique effective des caches et fichiers (T08, T10, T11), affichage réel sur écran verrouillé (T12),
contrôle d'accès serveur (T13), horaires effectifs des alarmes Android (§5.3).

Exécution : `./gradlew test` (JDK 17+).

## 3. Interprétations retenues (à valider)

Ces choix comblent des cas que le CDC ne tranche pas. Chacun va dans le sens de la non-divulgation (CDC §14.3).

1. **Mode « À la lecture », consultation en cours.** Le pseudo-code §14.2 calcule `min(A, first_view_closed_at)`
   sans définir la valeur tant que la vue n'est pas fermée. Retenu : seule la limite A s'applique pendant la consultation.
2. **Mode « À la lecture », consultation interrompue** (processus tué, redémarrage). Sans règle, le message resterait
   lisible jusqu'à A. Retenu : toute nouvelle ouverture vaut clôture de la première consultation ⇒ expiration.
3. **Date d'envoi de référence.** Le CDC utilise `sent_at` sans préciser son origine. Une date déclarée par l'expéditeur
   peut être falsifiée (horloge avancée ⇒ message prolongé). Retenu : `min(date déclarée, date de réception par le relais)`.
4. **Horloge locale.** Heure retenue = max(heure système, dernière heure serveur + horloge monotone, plus haut instant
   déjà observé). Reculer l'horloge système ne prolonge rien ; l'avancer ne fait qu'expirer plus tôt.
5. **Regroupement des rappels.** Fenêtre de 5 minutes, déclenchement à l'heure du premier rappel du groupe (aucun
   rappel n'est retardé). En mode discret, le nombre de messages n'est pas affiché.

## 4. Points de vigilance relevés dans le CDC

| # | Constat | Conséquence | Proposition |
|---|---|---|---|
| V1 | **Le mode « 24 h après lecture » est mathématiquement équivalent à l'absence de lecture.** Comme `first_read_at ≥ sent_at`, on a toujours `first_read_at + 24 h ≥ sent_at + 24 h` : le plafond s'applique dans 100 % des cas, pas « généralement » (§4.1). Vérifié par test. | Le libellé laisse croire à l'utilisateur un délai de 24 h après sa lecture, qui n'existe jamais. | Renommer en « Jusqu'à la limite (24 h après envoi) » ou remplacer par une durée intermédiaire (les options 30 et 60 min ont été écartées, §19). Décision produit. |
| V2 | Le CDC ne dit pas si **la copie de l'expéditeur** suit la même expiration ni si l'expéditeur voit la lecture. | Persistance possible du contenu côté émetteur, contraire à l'esprit du produit. | Appliquer la limite absolue à la copie émetteur ; préciser l'effet de la lecture. |
| V3 | **Multi-appareils** (§14.3, §19) : une lecture sur un appareil doit couper l'accès sur les autres. | Sans propagation, le mode 15 min est contournable par un second appareil. | Événement de lecture chiffré entre appareils du même compte ; à concevoir avant activation du multi-appareil. |
| V4 | **Alarmes exactes Android** (§5.3) : l'accès aux alarmes exactes est restreint par Android selon la catégorie d'application. | Les rappels de 15 min peuvent être décalés. | Vérifier l'éligibilité réelle de GhOst aux permissions d'alarme exacte sur la documentation [S5] en vigueur ; sinon assumer des rappels approximatifs (l'expiration logique n'en dépend pas, ce que garantit déjà le module). |
| V5 | Le CDC (§19) ne fournit **ni gamme Android, ni budget, ni calendrier**. | Le lot L1 ne peut être chiffré. | Étude de faisabilité préalable, comme le prévoit le CDC. |
| V6 | Logo : le CDC indique qu'il est « fourni dans le projet » (§10.1) ; il n'était pas dans le dépôt. | — | Extrait du PDF en 1254 × 1254 px : `assets/brand/ghost-logo-1254.png`. Fichier source vectoriel à obtenir pour les icônes Android adaptatives. |

## 5. Prochaines étapes proposées (L1)

1. Module Android `:app` (Kotlin + Jetpack Compose, CDC §12.1) avec jetons de couleur §10.2 et navigation §11.
2. Persistance locale chiffrée et purge effective (caches, miniatures, fichiers temporaires) branchées sur `MessageLifecycle`.
3. Choix et revue de la bibliothèque E2EE (PQXDH + Double Ratchet, [S1], [S2]) : licence, maintenance, audit.
4. Backend minimal (Go ou Rust, §12.1) : annuaire, préclés, relais avec `RelayRetention` comme spécification de référence.
