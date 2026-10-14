# PressIA — notes pour les agents

## Projet

Lecteur RSS Android (Feedly / Inoreader / flux locaux). **100 % Jetpack Compose** : il n'existe
aucun `res/layout`, aucun ViewHolder/Adapter, aucun `viewBinding`. Les 4 vues articles
(`list` / `card` / `magazine` / `compact`) sont des branches d'un `when (mode)` dans
`ArticleRow` (`app/src/main/java/com/pressia/ui/Components.kt`).

- package / applicationId : `com.pressia`
- activité launcher : `com.pressia.MainActivity`
- compileSdk / targetSdk 34, minSdk 26
- AGP 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.25, Gradle 8.7
- unique test unitaire : `app/src/test/java/com/pressia/PressLogicTest.kt` (pas de androidTest)

## Chaîne d'outils (machine-specific)

`gradle` **n'est pas dans le PATH** et `gradlew.bat` n'est qu'un stub (`gradle %*`) : il n'y a ni
`gradle-wrapper.jar` ni distribution dans `~/.gradle/wrapper/dists`. Le `README.md` dit
`./gradlew`, **ça ne marche pas sur cette machine**. Utiliser les copies déjà présentes dans
le dossier tools temporaire :

| Outil | Chemin |
|---|---|
| JDK 17 | `%LOCALAPPDATA%\Temp\opencode\tools\jdk-17.0.20.1+1` |
| Gradle 8.7 | `%LOCALAPPDATA%\Temp\opencode\tools\gradle-8.7\bin\gradle.bat` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (cf. `local.properties`) |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| emulator | `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe` (AVD **Pixel_10**) |

Ces outils sont hors du dépôt : s'ils disparaissent, les retélécharger (le ZIP de Gradle 8.7 est
normalement déjà dans le même dossier).

Préambule PowerShell à poser avant tout appel Gradle :

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Temp\opencode\tools\jdk-17.0.20.1+1"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$g = "$env:LOCALAPPDATA\Temp\opencode\tools\gradle-8.7\bin\gradle.bat"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

`--offline` fonctionne : les dépendances sont en cache dans `~/.gradle`. Ne le passer que si
rien n'a changé côté dépendances ; sinon le build échoue sur une résolution réseau.

## Compilation / tests (vérifiés)

```powershell
& $g :app:compileDebugKotlin --console=plain --offline    # vérif rapide après une modif
& $g :app:assembleDebug     --console=plain --offline    # APK debug
& $g :app:testDebugUnitTest --console=plain --offline    # tests unitaires
```

APK produit : `app/build/outputs/apk/debug/app-debug.apk`.

## Émulateur et pilotage de l'app (adb + UIAutomator)

Un émulateur est souvent déjà démarré sur `emulator-5554`. Sinon :

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_10
& $adb devices                                          # état des appareils
```

Installer / (re)lancer / logs :

```powershell
& $g :app:installDebug --console=plain --offline        # échoue si aucun émulateur lancé
& $adb install -r app/build/outputs/apk/debug/app-debug.apk              # variante manuelle
& $adb shell am start -n com.pressia/.MainActivity      # (re)lance l'app
& $adb logcat --pid=(& $adb shell pidof com.pressia)    # logs de l'app
```

Dumper l'interface (script utilitaire, équivalent aux deux commandes suivantes) :

```powershell
pwsh -NoLogo -NoProfile -File "$env:LOCALAPPDATA\Temp\opencode\dump.ps1"
& $adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml
& $adb -s emulator-5554 pull /sdcard/ui.xml "$env:LOCALAPPDATA\Temp\ui.xml"
```

`$env:LOCALAPPDATA\Temp\ui.xml` contient l'arborescence de l'écran courant. Repérer un node
par son text / content-desc / resource-id, calculer le centre de son bounds, puis :

```powershell
& $adb -s emulator-5554 shell input tap <X> <Y>
& $adb -s emulator-5554 shell input keyevent KEYCODE_BACK
& $adb -s emulator-5554 shell input swipe 540 1400 540 400 300
& $adb -s emulator-5554 shell "am force-stop com.pressia; monkey -p com.pressia -c android.intent.category.LAUNCHER 1"
```

Méthodologie : dump après chaque action, attendre 1–3 s selon la transition, re-dumper.

**Touches dans une WebView : viser ~90 px plus bas.** `input tap` atterrit environ 90 px
trop haut dans le contenu web (constaté : le bouton gris « Sign in with Google » se prenait
pour le vert « Sign in with Email » juste en dessous, les touches « Suivant » ouvraient les
CGU voisines). La WebView n'expose aucun node (opaque pour uiautomator) : passer par
capture d'écran (`screencap` + `read` du PNG, 1:1 avec l'écran 1080×2424) et compenser
`tap(X, Y+90)`. Champs hauts tolérants ; boutons ~80 px : rater = page voisine, revenir
en arrière via la flèche retour de l'app (jamais `KEYCODE_BACK` dans une page web : il
risque de quitter l'écran au lieu de fermer le clavier — préférer toucher une zone neutre
pour le replier).

## Images / miniatures (non intuitif, tout est là)

- Stockage : `ThumbStore` (`data/local/ThumbStore.kt`), un fichier `SHA-1(url).jpg` dans
  `filesDir/thumbs`. Gabarit = **plus grande miniature affichée** (bannière magazine :
  largeur écran × 180 dp), jamais l'original full-size. Une URL partagée ne se télécharge qu'une fois.
- Suivi : `ArticleEntity.imageCached`. `syncThumbs()` (`PressRepository`) télécharge tout ce qui
  manque (lus + non lus, 4 en parallèle, inactif si « Images hors-ligne » coupé ou « Wi-Fi
  uniquement » hors Wi-Fi). Appelé en fin de `syncDown`, `syncReadLater` et `backfill` : **toute**
  sync (bouton, lancement, worker, connexion de compte) récupère les images.
- `clearCache()` / `clearImages()` effacent les fichiers **et** remettent `imageCached` à 0 :
  la prochaine sync, quelle qu'elle soit, récupère tout. Ils purgent aussi les HTML extraits
  via Readability (`content = summary`, `fullFetched = 0`) : les articles sont conservés avec
  leur extrait RSS, la prochaine ouverture complète ré-extrait.
- Listes : les 3 `AsyncImage` d'`ArticleRow` passent par `thumbModel()` (fichier local si
  présent, sinon URL paresseuse au scroll — aucun préchargement de liste). L'écran article garde
  les URL pleine qualité (hero / inline / viewer).

## Pièges connus

- Modifier une vue article = toucher `ArticleRow` dans `Components.kt`. Le `MetaLine` (source +
  étoile + heure) est partagé par les 4 vues ; lui donner un paramètre `modifier` plutôt que
  d'en dupliquer une variante.
- La position du rond lu/non-lu (`dotAtStart`) pilote aussi celle de l'étoile de swipe : les deux
  doivent rester du même côté, sinon l'information saute d'un bout à l'autre de la ligne au geste.
- Les champs de config OAuth (`FEEDLY_CLIENT_ID`, `INOREADER_APP_ID`, …) sont lus dans
  `local.properties` ou l'environnement ; ils sont vides par défaut et ne servent qu'au flux
  OAuth générique (`OAuthWebViewScreen`, utilisé par Inoreader). **Feedly n'en a pas besoin** :
  sa connexion passe par la page officielle intégrée (`FeedlySignInScreen`, identifiants
  saisis sur feedly.com, jeton de session récupéré automatiquement) — ne pas conclure
  qu'un compte distant ne peut pas s'authentifier sous prétexte que ces champs sont vides.
- Pas de migration Room à écrire pour une simple requête DAO ou colonne déjà existante ;
  `exportSchema = false`, version DB actuelle : 5.
- Réglage ajouté, modifié ou supprimé = cycle complet obligatoire : clé dans `Prefs`,
  état + `reloadFromPrefs`/`save()` dans l'écran Réglages, entrée dans `Prefs.ENTRIES`
  (sinon absent de l'export/import — test `entrees de preferences` à étendre), valeur
  par défaut cohérente partout où elle est lue.
- Commits auto : `.opencode/plugins/autocommit.js` commit + push tout seul (`git add -A`,
  message `Auto-commit <date>`) à chaque `session.idle`. Un commit manuel avec un vrai message
  reste préférable quand l'utilisateur le demande.
