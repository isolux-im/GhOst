# GhOst

Messagerie privée instantanée — éphémère, chiffrée de bout en bout, pseudonyme (Android, écosystème Open).

<img src="assets/brand/ghost-logo-1254.png" alt="Logo GhOst" width="160">

## Règle cardinale

Un message disparaît à la plus proche de deux échéances :

- **limite absolue** : envoi + 24 h ;
- **option après lecture** choisie par l'expéditeur : à la lecture, 15 min ou 24 h.

`expiry_at = min(sent_at + 24h, échéance après lecture)` — un message non lu expire à envoi + 24 h.

## Contenu du dépôt

| Chemin | Rôle |
|---|---|
| `docs/GhOst_Cahier_des_Charges_V1.0_2026-11-09.pdf` | Cahier des charges de référence (V1.0) |
| `docs/CADRAGE.md` | Traçabilité, interprétations retenues, points de vigilance |
| `core/expiry` | Domaine Kotlin pur : expiration, états, rappels, horloge de confiance, rétention relais |
| `assets/brand` | Logo validé |

## Construire et tester

```bash
./gradlew test
```

JDK 17 ou supérieur requis. Les tests reprennent les scénarios de recette T01 à T14 du cahier des charges
pour la partie logique.
