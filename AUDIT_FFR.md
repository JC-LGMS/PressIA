# Audit PressIA - Diff AGENTS.md (Français)

## Nouvelles politiques et fonctionnalités

### 1. Miniature déclarée par le flux
- **Nouveau gabarit** : carré de la largeur d'écran (pas ×180 dp)
- Image large/haute stockée à largeur exacte de l'écran
- Exemple : 2400×1600 → 1080×720 (ancien : 742×495)
- Jamais full-size, jamais upscaled

### 2. Images du corps - Cache disque Coil
- Mécanisme favicons étendu aux images d'article
- `Coil.setImageLoader` pose `respectCacheHeaders(false)`
- `refreshFavicons()` ne vide plus tout le cache disque
- Images servies depuis disque sans réseau

### 3. syncImages() - Nouvelle fonction
- Sous `imagesMutex`
- Articles `imageCached = 0` (lus + non lus, 6 parallèles)
- Liste images `articleImageUrls` (miniature + `<img>` content/summary, dédoublonnées)
- Miniature ThumbStore + autres via Coil
- Image manquante → article en attente (repris plus tard)
- Marquage par article, pas par URL
- Appelé fin `syncDown` (`withThumbs = true`) + `backfill`
- Toute sync récupère les images

### 4. Ne jamais relancer depuis syncReadLater
- Constaté : 26/32 images doublons
- Pipeline lance déjà passe parallèle (`supervisorScope`)
- Deux passes simultanées = mêmes URLs téléchargées deux fois

### 5. deleteLocalFeed + vacuum()
- Ancien : articles + gcThumbs + images Coil
- **Nouveau** : + `vacuum()` + `PRAGMA wal_checkpoint(TRUNCATE)`
- Sans checkpoint : VACUUM dans WAL, fichier principal garde taille
- Avec checkpoint : 1,8 Mo → 120 Ko (contre 1,8 Mo → 1,9 Mo sans)
- "Articles" cache = fichiers `pressia.db*` (db + WAL + shm)

### 6. Statistiques préservées
- `reading_events`/`extract_events` partent plus avec suppression
- Effacement remet stats à zéro (ancien comportement)
- **Remise à zéro MANUELLE** : écran Stats → `resetStats`
- Pose `Prefs.STATS_SINCE` borne basse (`statsLowerBound`)
- Indispensable compteur « passés en lu sans ouvrir »

### 7. Orphelin MainActivity
- `LaunchedEffect` remet périmètre « Tous les articles » + `lastContent` accueil
- Si supprimé depuis autre écran (déconnexion, désabonnement)
- Sinon retour sur liste vide
- Validation dossier : interroger `vm.folders` ET `vm.categories`
- Dossier fusionné = identifiant `merged:…` absent catégories brutes (faux positif)

### 8. Ajout flux local - première synchro annoncée
- `lockSync()` + `syncMsg` appariés en `finally`
- Sans cela : ajout muet, sans bandeau/snackbar pendant tout téléchargement

## Points déjà audités (récapitulatif)

| # | Point | Statut |
|---|-------|--------|
| ✅ | ThumbStore/syncThumbs/syncReadLater | Correct - mutex prevents doubles |
| ✅ | pushQueue/pushMutex | Correct - dédié, non réentrant |
| ✅ | Local feeds keepRead | Correct |
| ✅ | syncRateLimit tracking | Fonctionnel |
| ❌ | LocalRssFetcher HTTP cleartext | ⚠️ Toujours nécessaire : `AndroidManifest.xml` |

## Recommandations prioritaires (français)

1. **Critique** : Ajouter `android:usesCleartextTraffic="true"` dans `AndroidManifest.xml` si ce n'est pas fait - cela casse silencieusement le fetching de flux RSS locaux sur Android 14+.

2. **Élevée** : Valider la nouvelle politique miniature (gabarit carré largeur écran) - les dimensions déclarées dans `ThumbStore.maxThumbSize` doivent correspondre au nouveau carré, pas à l'ancien ×180 dp.

3. **Élevée** : Tester `syncImages()` sous `imagesMutex` - s'assurer qu'elle est bien appelée à la fin de `syncDown` et `backfill`, et que le marquage par article (pas par URL) fonctionne correctement.

4. **Moyenne** : Vérifier `deleteLocalFeed` + `vacuum()` + checkpoint - confirmer que l'espace disque est libéré (1,8 Mo → 120 Ko attendu).

5. **Moyenne** : Tester la préservation des stats après suppression compte/flux - le `resetStats` doit fonctionner et `Prefs.STATS_SINCE` doit être correctement posé.

6. **Basse** : Valider le comportement orphelin `MainActivity` - suppression de flux/dossier depuis autre écran doit remettre le contexte correctement.

7. **Contexte** : Le plugin `opencode-rate-limit-fallback` (question initiale) est un plugin OpenCode pour automatiser les retry sur rate limits - pas directement lié au code PressIA mais pertinent pour l'automatisation future.