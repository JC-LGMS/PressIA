# PressIA — successeur moderne de Press (lecteur Feedly/offline)

UI et fonctions inspirés de **Press 1.5.4** :
- Drawer catégories / abonnements, flux stream, lu/non-lu, favoris, thèmes clair/sombre (+AMOLED), partage système, hors-ligne complet, sync planifiée, Wi-Fi only, taille max du cache, images offline, taille police, vues liste/carte/magazine, article complet via double-tap (Readability maison).
- Non dépendant de Feedly Pro : fonctionne avec le niveau gratuit Feedly Cloud v3, **Inoreader** (`https://www.inoreader.com/`), ou **100 % local RSS/Atom** sans compte.

Ajouts demandés :
- **Recherche avec périmètre** : racine = tous les flux ; dossier/flux = périmètre courant (serveur si dispo, sinon LIKE offline).
- **Déduplication** : hash titre+lien (`dedupHash`), écran Doublons, marquage lu manuel / « tout sauf le + récent ».
- **Stats de lecture** : total 30 j, top flux, activité/jour (tables `reading_events` + `extract_events`).
- **Multi-comptes** : plusieurs comptes Feedly/Inoreader coexistent, chacun identifié par `service#empreinte-du-jeton` (`Accounts.kt`), sans jamais se confondre.
- **Sauvegarde/restauration** : export JSON sectionné — préférences, stats, comptes/flux, favoris ; les jetons ne sont jamais exportés par défaut (`Backup.kt`).

## Vidéo YouTube (intégrée)

- Le lecteur est désormais une **WebView** qui charge la page de lecture (`m.youtube.com/watch`) au lieu de l'intégration (`youtube.com/embed/…`) : l'application peut contrôler la lecture et la visibilité de manière fiable.
- **Contrôles** injectés dans la page :
  - Surface transparente (`#pressia-shade`) sur toute la vidéo : simple appui → lecture/pause ; double appui à gauche/droite = ±10 s.
  - Barre en bas (`#pressia-bar`, 50 px) : lecture/pause, temps écoulé / durée, barre de progression (appui/glissement), son, réglages.
  - Menu (`#pressia-menu`) : vitesse de lecture (0,5× à 2×) et « Lecture en boucle ».
- Le **son est géré par l'application** : l'utilisateur peut couper/restaurer le son ; l'icône suit cet état, pas celui du lecteur YouTube lui-même.
- **Plus d'écran noir** : la page de lecture ne se lance plus vide ; l'affiche et le bouton d'ouverture restent visibles jusqu'à la première interaction.

## Structure
- `data/local` : Room (`categories`, `subscriptions`, `articles`, `sync_queue`, `reading_events`, `extract_events`) + DataStore `Prefs`.
- `data/remote` : interface `FeedProvider` (`FeedProvider.kt`) et `Providers.kt` (`FeedlyProvider` OAuth2 + `cloud.feedly.com/v3`, `InoreaderProvider` (`inoreader.com/reader/api/0`), `LocalProvider`), `CloudApis.kt` (modèles + services Retrofit/kotlinx-serialization), `LocalRssFetcher.kt` (parsing RSS/Atom), `OAuth` (URLs d'autorisation, échange code→jeton, deep link `pressia://oauth`).
- `data` : `PressRepository` offline-first (lecture Room, `syncDown`, `pushQueue` bidirectionnelle, `ensureFull`, `syncReadLater`), `Accounts.kt` (registre de comptes), `Backup.kt` (export/import JSON).
- `data/sync` : `SyncWorker` (WorkManager périodique, contraintes Wi-Fi, synchro « Read later ») ; miniatures via `ThumbStore` (`SHA-1(url).jpg`, taille de la plus grande miniature affichée) et `syncThumbs()` (appelé en fin de `syncDown`, `syncReadLater` et `backfill`).
- `data/readability` : extraction article complet Jsoup (double-tap).
- `di` : `Locator` (instanciation manuelle du repository, partagée UI et WorkManager).
- `ui` : Compose Material3 (drawer, stream, abonnements, article, recherche, doublons, stats, réglages, comptes) autour du `PressVM` ; `Components.kt` et `ExtraScreens.kt` regroupent les écrans secondaires.
- Vues articles : liste / carte / magazine / **compacte** (favicon ou initiale, petits titres/dates).
- Accueil au choix : **Abonnements** (dossiers + compteurs non-lus) ou **Tous les flux**.
- Bouton de raccourci par ligne au choix : favori / lu-non-lu / partage.
- Article : bouton marquer non-lu, site d'origine, drawer accessible, pas de titre redondant.
- Réglages : tailles de police titres/dates/textes, thème aussi dans le drawer, cache jusqu'à 10 000 articles/flux, fréquence de sync séparée.
- Article : titre redimensionnable et cliquable (navigateur), site + date sur une ligne, image principale + images inline, notice FR si extraction bloquée.
- Couleur dominante configurable (rouge/bleu/vert/violet/orange), pastilles lu/non-lu grises, favicon dans le drawer et le top-bar, toggle rapide lus/non-lus (œil), snackbar de résultat de sync, surbrillance drawer pilotée par la navigation (retour arrière correct).
- Favicons réels (service `s2/favicons` via host, cache Coil offline, initiale en repli), nom de site `prettyFeedTitle`, palettes couleur dominante + fond, titre d'article redimensionnable/cliquable avec images inline, pastilles grises réduites, thème dans l'en-tête du drawer.

## Comptes (100 % in-app, sans navigateur externe)
**Feedly : aucun identifiant à renseigner.** Écran **Comptes → « Se connecter avec Feedly »**
(`FeedlySignInScreen`) : la page de connexion officielle s'ouvre dans une WebView intégrée,
l'utilisateur y saisit ses identifiants habituels (e-mail/mot de passe, Google, Apple),
puis PressIA récupère automatiquement le jeton de session (stockage web, cookies en filet),
le vérifie, enregistre le compte et revient. Pas de `FEEDLY_CLIENT_ID` à créer : ce flux
utilise le `client_id=feedly` public (+ PKCE).
**Inoreader et variante OAuth générique** : la WebView charge la page d'autorisation, intercepte le retour
`pressia://oauth/…`, échange le code contre un jeton (`OAuth.feedlyToken` / `inoreaderToken`)
et enregistre le compte.
Redirect URIs (à déclarer telles quelles dans votre app Feedly/Inoreader) :
`pressia://oauth/feedly`, `pressia://oauth/inoreader` (aussi déclarées en deep link manifest en secours).

## À propos / Journal des modifications

Le journal des modifications (changements consignés par version, avec date et heure de chaque commit) est visible dans l'application : toucher le **numéro de version** (bas du tiroir ou écran À propos) ouvre la page `ChangelogScreen`. Cette page lit `CHANGELOG` (`data/ui/Changelog.kt`) : chaque version et chaque item y sont saisis à chaque commit significatif, avec le titre et le détail du message de commit, ainsi que l'heure (`HH:mm`).

Le historique initial (208 commits, jusqu'au 2026-10-25) est conservé dans `CHANGELOG_HISTORY` et ne change plus.

## Build
```powershell
$env:FEEDLY_CLIENT_ID="..."
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Min SDK 26, target/compile 34, Kotlin 2.0, Compose BOM 2024.10, Material3 1.3, Room 2.6.1, Paging 3.3, DataStore 1.1, WorkManager 2.9, Coil 2.6, Retrofit 2.11 / OkHttp 4.12 / kotlinx-serialization 1.7, Jsoup 1.17.
