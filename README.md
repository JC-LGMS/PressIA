# PressIA — lecteur RSS Android, successeur moderne de Press

Lecteur RSS/Atom 100 % Jetpack Compose, hors-ligne d'abord : Feedly, Inoreader,
FreshRSS ou flux locaux, avec dossiers, lu/non-lu, favoris, article complet et
images conservés sur l'appareil.

## Ce que fait l'application

- **Comptes** : Feedly et Inoreader par leur page officielle intégrée (jeton de
  session ou cookie repris automatiquement), FreshRSS par l'adresse du serveur
  avec identifiant et mot de passe, et des flux RSS/Atom saisis à la main, sans
  compte. Plusieurs comptes coexistent sans se confondre.
- **Dossiers** : fusionnés (même nom chez tous les comptes) ou séparés (un
  dossier par compte). Créer, renommer ou supprimer un dossier part chez le
  compte qui le porte ; l'application le crée chez lui au premier flux rangé
  dedans, et affiche le compte sous chaque dossier au déplacement. « Monter » et
  « Descendre » réordonnent l'écran, dossier fusionné compris, et l'ordre part
  chez Feedly (Inoreader n'a pas d'ordre de dossiers).
- **Flux** : une icône « + » (Accueil, menu d'un dossier, Comptes) ajoute un
  flux — local, ou chez le compte choisi : l'abonnement part au compte avant
  d'être écrit sur l'appareil, et un refus ne laisse rien. « Se désabonner » le
  retire aussi du compte, efface ses articles et ses images, et s'annonce dans le
  bandeau du bas, étape par étape.
- **Lecture** : quatre vues (liste, carte, magazine, compacte), quatre tailles de
  police, thème clair/sombre/AMOLED et apparence automatique selon la lumière
  ambiante. Un article ouvert est conservé hors-ligne (extrait du flux, images).
- **Sélection** : le texte d'un article se sélectionne (appui long) et le menu
  propose « Rechercher dans l'application » (bibliothèque locale, dans le flux de
  l'article), « Rechercher sur Internet » ou « Traduire ».
- **Article complet** : extraction Readability maison (Jsoup) au double-tap,
  conservée en base et préservée par les resynchronisations.
- **Vidéos** : YouTube lu dans l'article (lecteur intégré, plein écran de
  l'application, titre sous le lecteur) ; un fichier vidéo d'un flux se lit dans
  la page.
- **Recherche locale** (titre, auteur, ou texte), **doublons** (titre normalisé,
  groupés, « tout sauf le plus récent »), **statistiques de lecture**
  (ouvertures, temps par flux, activité par jour, passés en lu sans ouvrir — le
  temps ne compte que l'application au premier plan).
- **Hors-ligne** : file montante des lus/étoiles poussée au retour du réseau,
  images et miniatures téléchargées sous plafond, réglages « Wi-Fi uniquement »,
  quota par flux et durée d'historique.
- **Synchro en arrière-plan** (option, coupée par défaut) : une passe toutes les
  X heures (1 à 24), application fermée — mêmes articles, images et « Read
  later » que le bouton, sans rien afficher. Android décide du moment exact ;
  « Wi-Fi uniquement » devient une contrainte, la passe attend alors le réseau.
- **Sauvegarde** : export/import JSON par sections (réglages, stats, comptes,
  favoris et lus, jetons seulement si demandés), import plafonné à 20 Mo, OPML
  pour les flux locaux (charset du fichier respecté), et copie automatique
  silencieuse pour la sauvegarde Google du téléphone.

## Structure

- `data/local` : Room (`categories`, `subscriptions`, `articles`, `sync_queue`,
  `reading_events`, `extract_events`) + DataStore `Prefs`.
- `data/remote` : contrat `FeedProvider` et ses implémentations
  (`FeedlyProvider`, `InoreaderProvider` — FreshRSS en hérite —, `LocalProvider`),
  services Retrofit (`CloudApis.kt`), analyseur RSS/Atom (`LocalRssFetcher.kt`),
  OAuth (`OAuth.kt`).
- `data` : `PressRepository` (synchro descendante et montante, images, rétention),
  registre des comptes (`Accounts.kt`), sauvegarde (`Backup.kt`, `AutoBackup.kt`,
  `Opml.kt`), lectures plafonnées (`Bytes.kt`).
- `data/sync` : passe de fond périodique (`BackgroundSync.kt`, WorkManager).
- `data/readability` : extraction du corps d'article (Jsoup).
- `di/Locator.kt` : instanciation partagée du dépôt.
- `ui` : Compose Material3 autour de `PressVM` (liste, article, abonnements,
  recherche, doublons, favoris, stats, réglages, comptes, journal).

Aucune vue XML : les quatre présentations d'article sont des branches d'un même
composable (`ArticleRow`).

## Comptes

**Feedly** : « Se connecter avec Feedly » ouvre la page officielle dans une
WebView ; le jeton de session est repris et vérifié, sans identifiant
d'application à créer.

**Inoreader** : même principe (page du site, cookie `al`), ou la connexion OAuth
si vous déclarez votre propre application (`pressia://oauth/inoreader`).
L'API exige aussi l'« authentification applicative » (`AppId`/`AppKey`), fournie
par défaut avec celles de l'extension officielle et remplaçables dans Comptes.

**FreshRSS** : adresse du serveur (API Google Reader active), identifiant et mot
de passe. Un serveur en HTTP simple se saisit avec `http://`.

## Build

```powershell
./gradlew :app:assembleDebug          # APK debug
./gradlew :app:testDebugUnitTest      # tests unitaires (JVM)
```

Min SDK 26, target/compile 34. Kotlin 2.0, Compose 1.7.8 (BOM 2025.03.01),
Material3 1.3.2, Room 2.6.1, DataStore 1.1, Coil 2.6 (SVG compris), WorkManager 2.9,
Retrofit 2.11 / OkHttp 4.12 / kotlinx-serialization 1.7, Jsoup 1.17.

## Version et journal

Le numéro de version avance à chaque modification livrée. Toucher le numéro de
version (bas du tiroir ou écran À propos) ouvre le journal des modifications,
qui liste chaque changement avec sa date, son heure et son détail.
