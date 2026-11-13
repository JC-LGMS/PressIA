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
- Compose : BOM `2025.03.01` (**1.7.8**), Material3 **1.3.2**. Le `compileSdk 34` est le plafond :
  Compose 1.8+ exige `minCompileSdk=35`, Compose 1.12 exige 37 **et** AGP 9.1. Deux dépendances
  restent épinglées pour la même raison : `material-icons-extended:1.7.8` (plus publiée au-delà)
  et Kotlin/AGP (non touchés lors de la montée Compose).
- tests unitaires dans `app/src/test/java/com/pressia/` (`PressLogicTest.kt`,
  `FeedlyAutoReadTest.kt`, `ReconnectShortcutTest.kt`) ; **aucun androidTest**

## Chaîne d'outils (machine-specific)

`gradle` **n'est pas dans le PATH** et `gradlew.bat` n'est qu'un stub (`gradle %*`) : il n'y a ni
`gradle-wrapper.jar` ni distribution dans `~/.gradle/wrapper/dists`. Le `README.md` dit
`./gradlew`, **ça ne marche pas sur cette machine**. Utiliser les copies présentes dans le dossier
tools temporaire :

| Outil | Chemin |
|---|---|
| JDK 17 | `%LOCALAPPDATA%\Temp\opencode\tools\jdk-17.0.21+9` (la copie `jdk-17.0.20.1+1` n'a plus `javac`, KSP échoue : « does not provide the required capabilities: [JAVA_COMPILER] ») |
| Gradle 8.7 | `%LOCALAPPDATA%\Temp\opencode\tools\gradle-8.7\bin\gradle.bat` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (cf. `local.properties`) |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| emulator | `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe` (AVD **Pixel_10**) |

Ces outils sont hors du dépôt : s'ils disparaissent, les retélécharger (le ZIP de Gradle 8.7 est
normalement déjà dans le même dossier). **Le dossier temporaire est vidé de temps à autre** (constaté
le 31/10 : seul le JDK restait). Avant de retélécharger Gradle, regarder ce qu'il y a déjà dans le
dépôt de distributions : `~\.gradle\wrapper\dists\gradle-8.10.2-bin\<haché>\gradle-8.10.2\bin\gradle.bat`
— une distribution complète y fonctionne avec AGP 8.5.2.

Préambule PowerShell avant tout appel Gradle :

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Temp\opencode\tools\jdk-17.0.21+9"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$g = "$env:LOCALAPPDATA\Temp\opencode\tools\gradle-8.7\bin\gradle.bat"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

`--offline` marche tant que le cache Gradle (`~/.gradle`) est complet. S'il manque un plugin ou une
dépendance de test (`serialization`, `junit`, `org.json`…), relancer **une fois sans** `--offline` :
le cache se complète, puis on revient au hors-ligne.

## Compilation / tests (vérifiés)

```powershell
& $g :app:compileDebugKotlin --console=plain --offline    # vérif rapide après une modif
& $g :app:assembleDebug     --console=plain --offline    # APK debug
& $g :app:assembleRelease   --console=plain --offline    # APK release
& $g :app:testDebugUnitTest --console=plain --offline    # tests unitaires
```

APK produits (nommage personnalisé dans `build.gradle.kts`) :
- `app/build/outputs/apk/debug/PressIA-<version>-debug-<date>.apk`
- `app/build/outputs/apk/release/PressIA-<version>-release-<date>.apk`

Le renommage est effectué par la tâche `renameApks` (hook `finalizedBy assemble*`) qui s'exécute après l'assemblage. Exemple : `PressIA-1.0.49-debug-20261108.apk`.

## Version et journal des modifications

- **Le numéro avance tout seul, une fois par évolution.** `app/build.gradle.kts` compare
  l'empreinte de `app/src/main` + du script à `sourceHash` dans `version.properties` : si elle a
  changé, `versionName` passe à la mineure suivante (`1.1`, `1.2`, … — deux composants, plus de
  correctif à trois chiffres depuis la 1.1) et `versionCode` augmente de 1, **avant** que
  `defaultConfig` ne les lise.
  - **Un incrément = une modification terminée, pas une compilation.** Corriger, rebuilder,
    revérifier laisse la version en place : seul le **premier** build d'une évolution
    l'incrémente. Le signal est le commit — le script interroge
    `git diff --quiet HEAD -- version.properties` ; tant que ce fichier porte un incrément non
    commité, la version est conservée (log : « conservée (incrément en attente de commit) »).
    Après le commit, la modification suivante ouvre la version suivante.
  - L'empreinte est réécrite **à chaque** build, même sans incrément : le fichier commité décrit
    exactement les sources commitées, sinon le build suivant incrémenterait à tort.
  - **Ce que couvre l'empreinte** : `app/src/main/**` (Kotlin, `res/`, manifeste…) et
    `app/build.gradle.kts` — pas les tests (`app/src/test`), ni la racine du projet (build.gradle
    racine, wrapper, `local.properties`), ni ce fichier : les toucher ne change rien à la version.
  - Ne jamais retoucher les numéros à la main.
- **Déroulé d'une évolution** (l'ordre compte) :
  1. modifier les sources et compiler autant que nécessaire : le premier build incrémente, les
     suivants conservent ;
  2. écrire l'entrée du journal (voir ci-dessous) ;
  3. **recompiler après la dernière édition** : `version.properties` doit décrire les sources
     telles qu'elles seront commitées, sinon le build suivant les verra changées et ouvrira une
     version de plus, sans rien de neuf derrière ;
  4. installer, vérifier à l'écran, puis commiter sources + journal + `version.properties`
     **ensemble** : un commit = une version.
- **Évolution abandonnée** après l'incrément (tout annulé) : rendre `version.properties` au
  commité (`git restore version.properties`) puis recompiler ; sinon le fichier porte un numéro
  pour un changement qui n'existe pas.
- **Ce que l'application affiche** : « v1.6 (2026-11-11 23:04) » sous le tiroir et sur l'écran À
  propos (le journal, lui, n'a plus d'en-tête : il s'ouvre sur le décompte des changements). Le
  numéro vient du `PackageManager`, la date de `BuildConfig.BUILD_TIME` — donc du **build de
  l'APK**, pas du commit. Un APK construit avant la dernière édition montre une date plus
  ancienne, et c'est ce qui le trahit.
- **Journal des modifications** (`ui/Changelog.kt`, écran `ChangelogScreen`) : ouvert en touchant
  le numéro de version avec sa date de build, en bas du tiroir ou sur l'écran À propos. **À
  chaque commit qui touche l'application, ajouter un `ChangelogItem`** (date, heure du commit,
  titre, détail = le message de commit) **en tête de la version courante** de `changelog()`
  (`Changelog.kt` ; l'historique de 208 commits est à part, dans `changelogHistory()` de
  `ChangelogHistory.kt`, jamais édité) :
  compléter l'entrée existante si la version n'a pas changé, en créer une si le build l'a déjà
  incrémentée. Un commit sans entrée laisse la page mentir sur ce qui a changé. **L'heure est
  celle du commit**, pas celle du build : la noter en dernier, puis recompiler une fois
  (l'incrément en cours est conservé) — deux entrées écrites trop tôt avaient une minute
  d'avance. **Contrôler l'écran après l'installation** (le nombre de changements doit augmenter) :
  un APK produit avant l'édition du journal affiche encore l'ancienne liste, et c'est le seul
  signal.
- **Un commit qui ne touche ni `app/src/main` ni `app/build.gradle.kts`** (tests, documentation,
  outillage — ce fichier compris) **ne change ni la version ni le journal** : l'empreinte ne
  couvre que ces sources, et la page ne consigne que ce qui est livré dans l'APK.
- L'historique initial (208 commits, jusqu'au 2026-10-25) est dans `ui/ChangelogHistory.kt`,
  généré depuis `git log` : **ne plus le modifier**. Seuls les commits qui touchent `app/src/main`
  ou `app/build.gradle.kts` y figurent (ni tests, ni documentation, ni outillage).

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
L'ordre de grandeur du dump identifie l'écran : menu contextuel (DropdownMenu) ≈ 4 KB, liste
d'articles 35–45 KB, détail d'article (WebView) 45–50 KB.

Pour **chronométrer** une animation, `uiautomator dump` est inadapté : il attend l'inactivité de
l'interface (2–4 s). Utiliser `screencap -p /sdcard/x.png` + `pull` (~400–700 ms par capture, à
soustraire du temps mesuré) ou une trace `logcat` temporaire, retirée avant le commit.

**La pile de navigation n'est pas un état d'écran.** `nav.currentBackStack` est un `StateFlow`
depuis Navigation 2.7 (lire `.value`) ; les arguments d'écran y sont conservés par entrée, ce qui
sert à vérifier un parcours retour (voir `contentReturnTarget` et `ARTICLE_ID`).

### Simuler un appui long (long-press)

`input swipe X Y X Y DURATION` (même départ et arrivée) est parfois pris pour un simple tap.

1. **`input motionevent DOWN` / `UP`** — le plus fiable :

   ```powershell
   & $adb -s emulator-5554 shell "input motionevent DOWN 540 473"
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell "input motionevent UP 540 473"
   ```

2. **Warm-up obligatoire** après `am force-stop` : le ComposeView n'enregistre pas les gestes
   longs tout de suite. Faire un `input tap` + `KEYCODE_BACK` **avant** de tenter le long-press :

   ```powershell
   & $adb -s emulator-5554 shell "input tap 540 473"            # warm-up
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell input keyevent KEYCODE_BACK    # retour liste
   Start-Sleep -Seconds 2
   & $adb -s emulator-5554 shell "input swipe 540 473 540 473 3000"
   ```

3. Relancer sans `force-stop` avec `monkey -p com.pressia -c android.intent.category.LAUNCHER 1`
   (plus rapide, le warm-up est déjà fait si l'app était au premier plan).

**Touches dans une WebView : viser ~90 px plus bas.** `input tap` atterrit environ 90 px trop
haut dans le contenu web (constaté : le bouton gris « Sign in with Google » se prenait pour le vert
« Sign in with Email » juste en dessous). La WebView n'expose aucun node (opaque pour
uiautomator) : passer par capture d'écran (`screencap` + `read` du PNG, 1:1 avec l'écran
1080×2424) et compenser `tap(X, Y+90)`. Champs hauts tolérants ; boutons ~80 px : rater = page
voisine. Revenir par la flèche retour de l'app, jamais `KEYCODE_BACK` dans une page web (il risque
de quitter l'écran au lieu de fermer le clavier) ; pour replier le clavier, toucher une zone
neutre.

**Flux RSS locaux : HTTP en clair autorisé** (`app/src/main/res/xml/network_security_config.xml`,
`base-config cleartextTrafficPermitted="true"`, référencé par le manifeste). Un flux servi en HTTP
— serveur de test de l'émulateur (`10.0.2.2:8899`), FreshRSS/Miniflux auto-hébergé, blog encore en
http — est donc lu normalement. Le drapeau est dans le fichier de politique réseau (et non dans un
attribut du manifeste) parce qu'Android ne sait pas restreindre cette permission aux seules adresses
privées. Un petit serveur Python maison (`ThreadingHTTPServer` sur `0.0.0.0:8899`, flux `/fN.xml`
+ images `/img/N.jpg`) suffit pour les tests : il compte les requêtes par URL, ce qui vérifie en
direct « une seule requête par image » et « tous les flux interrogés ».

**L'APK release est signé avec la clé debug** (`build.gradle.kts` : `release { signingConfig =
signingConfigs.getByName("debug") }`). Conséquence pratique : un APK **debug** s'installe par-dessus
l'application release avec `adb install -r` **sans perdre les données** (même signature), et devient
`run-as`-able → lecture directe de `databases/pressia.db` (`adb exec-out run-as com.pressia cat
databases/pressia.db > copie.db` puis lecture en Python ; pas de `sqlite3` sur l'image de
l'émulateur). Une app **release** installée, elle, ne l'est pas (`run-as: package not debuggable`).

## Flux RSS locaux (pièges)

- **L'identifiant d'un flux local EST son URL.** Un caractère de contrôle collé au clavier (NUL,
  invisible à l'écran) rendait la ligne inutilisable : OkHttp l'encodait en `%00`, YouTube
  répondait **404**, et l'app annonçait « flux ajouté et synchronisé » avec **zéro article** —
  l'échec était muet (`fetch` renvoyait une liste vide, `runCatching` avalait l'erreur).
  `sanitizeFeedUrl` (`data/remote/LocalRssFetcher.kt`) nettoie l'URL **à l'entrée** (retrait des
  caractères de contrôle, ajout de `https://` si le schéma manque) et **à la requête** (un flux
  déjà enregistré reste donc exploitable) ; `isSupportedFeedUrl` refuse `ftp://` etc.
  `PressRepository.repairLocalFeeds` (début de `syncDown`) remplace une ligne sale par sa version
  propre : la ligne et ses articles sont supprimés **sur l'identifiant exact** (SQLite compare les
  octets, NUL inclus — Room préserve le NUL dans le binding, il n'y a pas de troncature), puis le
  flux est réenregistré. Sans cette réparation, un flux sale et son jumeau propre donnaient
  **deux fois les mêmes articles**.
- `LocalProvider` ne doit interroger que les flux `provider = "local"` : les abonnements d'un
  compte portent une URL interne (`feed/…`) que le lecteur local ne sait pas interroger (il
  essayait, échouait, et le signalait à chaque synchro).
- **Pas de plafond sur le nombre de flux locaux** : `LocalProvider.stream` les interroge tous, en
  parallèle bornée (8 à la fois). Un `take(n)` en laissait silencieusement une partie sans
  articles (constaté avec 32 flux : 30 servis seulement).
- **Le nom du flux remplace l'URL dans les listes.** Un flux saisi à la main n'a pour étiquette
  que son URL — d'où « youtube.com ». `LocalRssFetcher.parseFeed` rend le **titre de la racine**
  (`<feed>` en Atom, `<channel>` en RSS : le nom de la chaîne YouTube, le nom du site), et
  `PressRepository` l'écrit dans `subscriptions.title` (`setFeedTitle`, qui n'écrase que si le
  titre est encore l'URL). Le titre d'une **entrée** ne se confond pas avec celui du flux.
- **Un flux qui répond 5xx ou 429 est retenté** (une fois, 1,5 s d'attente) : YouTube le fait
  régulièrement, et un 404 — lui — signifie que l'URL est fausse (insister ne sert à rien).
- **Les médias d'une entrée Atom peuvent être imbriqués** : YouTube met sa miniature et sa
  description dans `<media:group>`, hors du parcours des enfants directs → ni vignette ni résumé.
  `parse` parcourt donc toute la descendance (`getAllElements`). `media:content` n'est retenu comme
  image que s'il est **une image** (YouTube y met son lecteur Flash, `type` non image) : sans ce
  filtre la miniature devient une URL de lecteur, non téléchargable.
- **Date : la première trouvée l'emporte.** `published` (publication) passe avant `updated`
  (modification) dans l'ordre du document → c'est la date de publication qui est retenue.
- **Un flux local vide doit se dire** : `LocalFeedResult` (VM) distingue « URL refusée », « flux
  synchronisé (n articles) » et « flux ajouté mais aucun article reçu » ; l'échec HTTP est
  journalisé (`flux local <id> : HTTP 404`). La suppression d'un flux local est décrite plus bas
  (`deleteLocalFeed`).
- **Un corps de flux ou une page d'article est lu sous plafond** (`readCapped`, dans
  `data/Bytes.kt` ; même helper pour les miniatures) : 5 Mo pour un flux (`LocalRssFetcher.MAX_FEED_BYTES`), 8 Mo pour une
  page d'article (`Readability.MAX_PAGE_BYTES`). Huit flux sont interrogés en parallèle, et une
  page peut être énorme (archive, réponse sans fin, URL hostile) : sans plafond, la synchro
  mourait en `OutOfMemoryError`. Un dépassement est un **échec de récupération** (erreur
  journalisée côté flux local, `FullResult.Failed` côté Readability). Le corps est décodé avec
  le charset du `Content-Type`, jamais par `body.string()`, qui lit tout. `readCapped` lit
  jusqu'au bout d'un flux plus petit que le plafond (il ne s'arrête qu'au-delà) et ne connaît pas
  la taille à l'avance : quand le serveur l'annonce, `exceedsCap(body.contentLength(), …)` échoue
  **avant** la lecture, aux trois appelants (miniature, flux, page).

## Contenu HTML de l'article (texte, liens, vidéos)

- Le contenu affiché vient de `feedHtml(article)` (résumé/contenu du flux, ou copie Readability)
  puis de `articleBlocks(html, baseUrl)` (`ui/ArticleScreen.kt`), qui le découpe en `ABlock` :
  `T` texte, `I` image, `L` lien isolé, `V` vidéo, `Table` tableau. **Le HTML du flux n'est pas
  rendu tel quel** : les balises sont interprétées bloc par bloc (sélecteur `ARTICLE_SELECTOR` :
  `p, li, h1..h6, pre, blockquote, figcaption`, plus `img, video, iframe, table`).
- **Chaque bloc de texte a un genre** (`ABlock.Kind`) qui pilote son rendu : `Title1/2/3`
  (`h1`, `h2`, `h3..h6`), `Bullet` (`li`, puce), `Quote` (`blockquote`, italique), `Caption`
  (`figcaption`, petit et centré), `Body` sinon. Un sous-bloc garde le genre du parent (un `p`
  dans un `li` reste une puce) sauf si sa balise en impose un (un `h3` dans une citation est un
  titre). Avant, tout était rendu en paragraphe : un titre avait la taille d'une phrase, et un
  `figcaption` ou un `table` disparaissait entièrement.
- **Gras, italique, surlignage, soulignement et barré sont des plages du texte**
  (`ABlock.Span` : `bold`, `italic`, `highlight` pour `<mark>`, `underline`, `strike`) : `strong`/`b`,
  `em`/`i`/`cite`, `mark`, `u`/`ins`, `s`/`del`/`strike` ne coupent pas la phrase, ils s'ajoutent
  aux annotations de `blockText` (avec les liens) — le surlignage prend `primaryContainer` en fond,
  qui suit le thème et le fond choisi. `isUnderlined` / `isStruck` reconnaissent aussi le style en
  ligne (`text-decoration: underline` / `line-through`, fréquent dans les flux ; Readability retire
  les styles, pas les balises) ; les deux traits se combinent (`TextDecoration.combine`). Le style
  en cours est porté par `Inline` et copié à chaque élément rencontré, si bien que les plages ne se
  chevauchent pas.
- **Un `table` devient `ABlock.Table(head, rows)`** : `th` de la première ligne en en-tête (gras,
  fond plus marqué), lignes suivantes sur fond alterné, filets et colonnes de largeur égale. Les
  fonds sont dérivés de `onSurface` en transparence (et non de `surfaceVariant`, qui vaut le fond
  quand une couleur de fond est choisie). Ses cellules ne sont pas traitées comme des blocs à part
  (`isNestedBlock` écarte tout ce qui est sous un `table`).
- **Une `iframe` n'est pas forcément une vidéo** : seuls YouTube (toutes ses écritures) et les
  hôtes de lecteur connus (`isVideoEmbed` : Vimeo, Dailymotion, Twitch) donnent un
  `ABlock.V(embed = true)`. Toute autre iframe — carte d'un autre article
  (`wp-embedded-content` chez MiniMachines), formulaire, commentaires — devient un `ABlock.L`
  (son attribut `title`, à défaut l'hôte) : elle affichait sinon un bloc lecteur noir pour une
  page qui n'est pas une vidéo.
- **Les liens restent dans le flux du texte.** Un conteneur (`p`, `li`, `h1..h3`, `pre`,
  `blockquote`) est coulé **d'un seul tenant** : ses liens ne sont pas des blocs à part (ils
  passaient à la ligne au milieu d'une phrase) mais des plages cliquables d'un `ABlock.T`
  (`ABlock.Link(start, end, url)`), rendues par `blockText` en annotations de lien — couleur
  primaire, soulignées — dont le tap ouvre le navigateur externe. Seul un lien **hors** de tout
  conteneur reste un `ABlock.L`. Pièges de la construction : le texte vient de `wholeText` (et non
  de `text`, que jsoup rogne et qui souderait les mots de part et d'autre d'un lien) ; un seul
  blanc au raccord entre morceaux ; les éléments en ligne (`strong`, `em`, `span`…) sont parcourus
  récursivement (leur texte était perdu) mais un **sous-bloc** (`p` dans `li`, deux `p` dans une
  citation) reste un bloc à part ; un conteneur imbriqué dans un autre n'est pas sélectionné deux
  fois (`isNestedBlock`) ; les médias coupent le texte (ce qui précède est publié, le média prend
  sa place, la suite reprend) ; l'URL d'un lien n'entre pas dans `seen`, sinon le second passage
  du même lien amputerait la phrase.
- **Texte sélectionnable** : la colonne défilante de l'écran article est enveloppée dans un
  `SelectionContainer` (appui long, poignées, « Copier / Tout sélectionner »). Le balayage
  horizontal d'article continue de fonctionner : c'est le geste immédiat, la sélection demandant
  un appui long.
  Les deux actions de lecture — « Rechercher sur Internet » et « Traduire » — viennent d'un
  `TextToolbar` propre à l'écran (`ArticleTextToolbar`, posé dans `LocalTextToolbar` autour du
  `SelectionContainer`) : Compose 1.7 ne livre pas le texte sélectionné à la barre, si bien que
  chaque action **copie** la sélection (même rappel que « Copier ») puis relit le presse-papiers
  (`selectionText`). Ne pas chercher une API de sélection à la place : il n'y en a pas dans cette
  version ; monter de Compose la rendrait disponible (`TextToolbarState`).
  **Le menu est dessiné dans la fenêtre de l'écran, pas en `Popup`** (`SelectionActions`) : un
  `Popup` focusable n'y recevait ni l'appui à côté ni Retour (constaté sur l'émulateur). Il se pose
  à **écart constant du doigt** (`MENU_GAP`, 40 dp — plus haut qu'une ligne, sinon il couvre le mot
  touché, et assez large pour laisser voir les poignées) : au-dessus quand il y a la place, sinon
  dessous. Ne pas s'ancrer au `rect` de `showMenu` : c'est bien le rectangle de la sélection (un mot
  fait 160 px de large), mais Compose 1.7 le décale de ~65 dp vers le haut (mesuré à l'écran), d'où
  un menu plusieurs lignes au-dessus du mot touché. `selectionAnchor` (même fichier) mémorise le
  doigt en passant `Initial`, sans rien consommer. Un voile transparent ferme au
  premier appui ailleurs,
  et `BackHandler` le ferme aussi. Deux garde-fous dans `ArticleTextToolbar` : un `hide()` moins de
  250 ms après un `showMenu` est ignoré (un champ éditable voisin le déclenche), et la sélection
  fermée par l'utilisateur n'est pas rallumée par Compose (`dismissedRect`, 800 ms).
  **Les pages de texte ont le même menu** : `SelectableArea` (À propos, Journal des modifications)
  pose le même `SelectionContainer` + barre, avec Coller/Couper en plus quand la zone contient un
  champ éditable.
- **Appui long sur une image** (dans l'article comme dans la visionneuse plein écran) :
  `ImageActionsMenu` (même fichier) propose « Copier l'image », « Partager » et « Rechercher sur
  Internet ». **Copier pose l'image elle-même** sur le presse-papiers : le fichier (copie locale de
  `ThumbStore` quand elle existe, sinon les octets téléchargés sous plafond) est écrit dans
  `cacheDir/partage` et exposé par le `FileProvider` du manifeste (`res/xml/file_paths.xml`) —
  depuis Android 10 un `file://` y est refusé, et la description du clip annonce `image`. Partager
  envoie ce même fichier (`EXTRA_STREAM` + autorisation de lecture sur l'URI, type `image`) ;
  seule la recherche part de l'**URL**, Google Lens n'ayant pas d'autre entrée
  (`lens.google.com/uploadbyurl?url=…`). L'appui
  simple ouvre toujours la visionneuse, le double appui garde l'action configurée. Les deux images
  (bannière du flux et blocs du corps) passent par le même composable `ArticleImage` : ne pas leur
  redonner un `AsyncImage` nu, l'appui long y serait perdu.
- **Visionneuse plein écran** (`ImageViewer`) : un seul détecteur (`detectImageGestures`) porte
  l'appui simple (fermer), l'appui long (menu), le double appui (zoom 2,5× animé, puis retour à 1×)
  et le **double appui glissé** (le zoom suit le doigt tant qu'il n'est pas relevé). Ne pas le
  remplacer par un `detectTapGestures` à côté pour le double appui : les deux guettaient le second
  appui chacun de leur côté et celui de la fermeture expirait parfois le premier — le double appui
  glissé fermait la visionneuse au lieu de zoomer (constaté sur l'émulateur). Le pincement et le
  déplacement restent à `transformable`, avec `offset += pan * scale` : la couche grossit son
  contenu, les coordonnées du geste y sont divisées par le zoom (sans le `* scale`, le déplacement
  est lent dès qu'on a zoomé ; mesuré à l'écran, 300 px de doigt = 300 px d'image à 2,5×).
- **Vidéos** : deux cas distincts, à ne pas confondre :
  - `<video src="…mp4">` = **fichier média** → `ABlock.V(embed = false)` : affiche (attribut
    `poster`) cliquable, puis `VideoView` (`AndroidView`) en 16/9, pause sur
    `ON_STOP`/`ON_DESTROY`, arrêt à la sortie. Jamais d'autoplay (forfait, respect du lecteur).
    **La lecture démarre dans `onPrepared`, jamais juste après `setVideoURI`** : avant la
    préparation, `start()` ne fait rien et la lecture ne revient plus (constaté : bloc noir, ni
    son ni image, aucun journal `MediaPlayer`). Le `VideoView` doit aussi être créé **dans** la
    fenêtre (`factory` d'`AndroidView`), pas par un `remember` détaché.
  - `<iframe src="…/embed/…">` = **page web**, pas un média → `ABlock.V(embed = true)`. Pour
    **YouTube**, la vidéo est lue **dans l'application** (`YoutubePlayer`), sur la page
    d'**intégration** (`youtubeEmbedUrl` → `youtube.com/embed/<id>`), chargée avec un
    **User-Agent de navigateur** (`youtubeBrowserUserAgent`) : YouTube y sert la vraie page
    mobile, dont les commandes sont de **vrais éléments du document** (titre, son, sous-titres,
    roue, ⤢, barre d'avancée, temps) — il n'y a donc rien à poser à la place.
    La page de **lecture** `m.youtube.com/watch` a été essayée puis abandonnée : en WebView `wv`
    elle n'offre aucun contrôle utilisable (boutons peints sur un canevas, hors de portée des
    appuis), et surtout **elle se met en pause toute seule** quelques secondes après le début —
    pile relevée : `onVideoProgress` → `visibility.Xj()` → `pauseVideo` —, y compris avec un
    User-Agent de navigateur et sans feuille de style de l'application. Le lecteur du site, lui,
    joue normalement (aucune pause spontanée constatée).
    **`Referer` = `https://m.youtube.com/`** (`youtubeReferer`) : un `loadUrl` sans en-tête n'en
    envoie aucun (« Erreur 152 »), et `www.youtube.com` comme référent d'une intégration est
    refusé (une page YouTube ne peut pas en intégrer une autre) — vérifié en direct ; l'erreur
    s'affiche **dans** le bloc. La page est **habillée** par `youtubePlayerScript(youtubeSiteCss())` :
    géométrie du bloc 16/9, fond noir, pas de défilement. Ne pas réintroduire de détection
    d'erreur par lecture du texte de la page : elle se déclenchait à tort, basculait sur l'affiche
    et le tap ouvrait l'application YouTube installée. Les autres services d'intégration (Vimeo,
    Dailymotion…) restent ouverts dans le navigateur. L'affiche est l'image YouTube déduite de
    l'identifiant (`img.youtube.com/vi/<id>/hqdefault.jpg`).
    **Un seul pattern pour le fichier média et l'iframe** : `VideoPlayer` porte l'affiche
    (`VideoPoster`) et le **seul** état de lecture (`playing`) ; c'est l'appui sur l'affiche qui
    compose le lecteur — `VideoView` d'un côté, `YoutubePlayer` (donc la WebView et sa page) de
    l'autre. Un lecteur YouTube non lancé n'est **ni composé ni chargé** : ne pas revenir à un
    `YoutubePlayer` composé d'emblée avec son propre état « lancé » (double affiche, double
    logique, et un composable vivant pour rien). La page d'intégration n'a pas d'autoplay : c'est
    `youtubeSiteScript` qui démarre la lecture (geste de l'utilisateur,
    `mediaPlaybackRequiresUserGesture = false` aidant) et rétablit le son (`unMute`), YouTube
    démarrant muet sans geste. Le délai de secours de 20 s ne court qu'à partir du tap.
    Deux mesures : `touch-action:none` (`youtubeSiteCss`) évite que Chromium prenne un glissement
    horizontal pour un défilement et l'annule (`pointercancel` au bout de ~15 px) ; et un doigt
    posé sur le lecteur sort du balayage d'article (`detectArticleSwipe`, repère `playerGesture`)
    — sinon tirer la barre d'avancée changeait d'article.
    **Titre sous le lecteur** : `YoutubeTitleRow` l'affiche et l'ouvre dans YouTube ; il vient
    d'oEmbed (`youtubeTitle`, sans clé d'API, mis en cache dans `youtubeTitles`, lui-même borné à
    200 titres en LRU : une session qui parcourt des dizaines de vidéos n'accumule pas un titre
    par vidéo pour la vie du processus). La requête passe par le client OkHttp **partagé**
    (`Readability.http`, délais globaux dont un plafond total, annulation réelle) : un
    `HttpURLConnection` sans plafond global retenait un fil jusqu'au bout. La WebView est
    mise en pause avec l'écran (`onPause`/`onResume` sur `ON_PAUSE`/`ON_RESUME`) : sans cela le
    son continuait après un changement d'article.
  - **La touche Retour doit être rendue à l'application.** Quand la vidéo joue, l'appui est
    **consommé par la hiérarchie de vues** : ni la WebView, ni la vue plein écran, ni le
    `BackHandler` de l'écran, ni `onBackPressed` ne le reçoivent (constaté : `Activity.dispatchKeyEvent`
    le voit passer, `onBackPressed` n'est jamais appelé ; le même appui, vidéo en pause, suivait le
    chemin normal). Deux garde-fous, parce que le chemin dépend de qui a la touche :
    - `PlayerWebView` (la sous-classe de WebView que le lecteur utilise) intercepte `KEYCODE_BACK`
      **avant Chromium** et le rend au `OnBackPressedDispatcher` : sinon Chromium l'avale et
      **arrête la lecture** (`crMediaCodecBridge: Codec released`) au lieu de quitter l'écran ;
    - `PlayerBack.handler` : tant que le lecteur est affiché, `MainActivity.dispatchKeyEvent`
      confie la touche au gestionnaire de l'écran article — plein écran d'abord, puis
      `OnBackPressedDispatcher.onBackPressed()`, exactement le comportement habituel — et la
      consomme. Hors du lecteur, le gestionnaire vaut `null` et rien n'est intercepté.
    Vérifié dans les quatre cas : vidéo en lecture, en pause, plein écran, et retour **après** un
    aller-retour en plein écran.
  - **Le plein écran est celui de l'application** (`PlayerFullscreen` + `PlayerJsBridge`) : l'API
    Fullscreen de la page est refusée par la WebView. La WebView est **déplacée** dans un
    `FrameLayout` posé sur la racine de la fenêtre — même page, même instant de lecture ; la
    recréer relancerait la vidéo au début. La classe `pressia-full` étire l'image sur toute la
    hauteur, l'activité passe en paysage et les barres système s'effacent ; ✕ en haut à gauche, et
    la vue plein écran est libérée dans l'`onRelease` de l'`AndroidView` (`screen.release()`, qui
    couvre aussi la sortie de composition). Déplacer la vue coupe le média :
    `pressiaResume` est rappelé au retour (150/600/1500/3000 ms).
    **Un aller-retour en plein écran se voit à l'écran**, et se vérifie en trois points : la classe
    `pressia-full` et `window.pressiaFull` (page), l'icône du bouton sous la vidéo (agrandir →
    réduire, `FullscreenToggle`), et le ✕ posé par l'application (uiautomator le liste sous
    « quitter le plein écran »). Attention à la géométrie des appuis : en paysage la fenêtre
    commence ~141 px à droite du bord (encoche), et 1 px CSS vaut 2,625 px d'écran — viser le
    centre du bounds mesuré dans la page, jamais des coordonnées devinées.
    `PlayerFullscreen.enter` rend un **booléen** : si le déplacement échoue (vue détachée),
    l'appelant remet l'état à « réduit », sans quoi le bouton restait dans le mauvais sens.
  - **Écran noir à l'appui** : la WebView était peinte en noir (`setBackgroundColor(BLACK)`)
    avant d'avoir la taille de son bloc, ce qui noircissait **tout** l'écran une seconde. Fond
    transparent, noir dû à la boîte qui l'entoure (`clipToBounds`) et à la page.
- Une image **AOSP** d'émulateur n'a **aucun décodeur vidéo** (« no video decoder », « OMX service
  is not available ») : la lecture y échoue alors que le code est correct. L'AVD `Pixel_10` est une
  image `google_apis_playstore` ; en cas de doute, tester sur un appareil réel. L'image peut y
  manquer alors que le son passe : mesurer par **statistiques de pixels** (`shot.py` : moyenne,
  écart-type, mouvement sur deux captures) plutôt qu'en lisant une capture — un lecteur noir donne
  0 % de mouvement, une vidéo qui joue en donne plusieurs dizaines.

## Images / miniatures (non intuitif, tout est là)

- Miniature **déclarée** par le flux (`imageUrl`, bannière de liste et image de tête) :
  `ThumbStore` (`data/local/ThumbStore.kt`), un fichier `SHA-1(url).jpg` dans `filesDir/thumbs` —
  ou `SHA-1(url).png` quand l'image a des **pixels transparents** (logos, illustrations) : un canal
  alpha ne suffit pas à décider (beaucoup de photos sont livrées en PNG RGBA opaque, et le PNG y
  pèserait dix fois plus), d'où `hasTransparency`. `thumbFileNames` rend les deux noms, `cachedFile`
  cherche l'un comme l'autre et `gc` supprime les deux : ne pas revenir à une seule extension.
  Gabarit = **carré de la largeur d'écran** : c'est la plus grande taille à laquelle ce fichier est
  affiché (image de tête d'article, pleine largeur ; la bannière magazine de 180 dp en est un
  recadrage). Toute image plus large que haute est donc enregistrée à la largeur exacte de l'écran
  (2400×1600 → 1080×720), jamais l'original full-size, jamais d'agrandissement à l'affichage. Une
  URL partagée ne se télécharge qu'une fois.
- Images **du corps** : le cache disque de Coil, par préchargement — même mécanisme que les
  favicons (`ImageRequest` + `execute`, `memoryCachePolicy(DISABLED)` et `size(1,1)` : c'est le
  cache disque qui compte, pas le décodage). Trop nombreuses pour un fichier dédié au gabarit.
  `Coil.setImageLoader` pose `respectCacheHeaders(false)`, donc elles sont rangées sous leur URL et
  l'affichage les sert sans réseau (`thumbModel()`, URL → cache Coil). Corollaire :
  `refreshFavicons()` ne vide **plus** tout le cache disque (il retire les seules entrées de
  favicons, `purgeImages`) — « Actualiser favicons » jetait sinon les images préchargées,
  définitivement (`imageCached` reste à 1).
- Suivi : `ArticleEntity.imageCached` = « toutes les images de cet article sont là ». `syncImages()`
  (`PressRepository`, sous `imagesMutex`) prend les articles à `imageCached = 0` (lus + non lus, 6
  en parallèle, inactif si « Images hors-ligne » coupé ou « Wi-Fi uniquement » hors Wi-Fi), liste
  leurs images (`articleImageUrls` : miniature déclarée puis `<img>` de `content`/`summary`,
  dédoublonnées), met la miniature dans `ThumbStore` et les autres via Coil, puis marque
  l'article — une image manquante le laisse en attente (elle sera reprise ; marquage par article,
  pas par URL). **L'ordre de la file est la priorité** (`pendingImageWork`) : articles les plus
  récents d'abord (ordre de la requête), miniature puis images du corps de chacun, les
  miniatures perdues en dernier — deux tas (toutes les miniatures, puis tous les corps)
  reléguaient les images du dernier article derrière celles de la veille, alors que six
  téléchargements seulement tournent à la fois. Appelé en fin de `syncDown` (`withThumbs = true`)
  et de `backfill` : **toute** sync
  (bouton, lancement, worker, connexion de compte) récupère les images.
  **Le compteur « n/total » compte les images terminées, échecs compris** : en ne comptant que
  les réussites, il se figeait sur « 42/78 » pendant que le reste épuisait son délai (10 à 20 s
  par image, six à la fois) — la synchronisation semblait bloquée alors qu'elle avançait (constaté
  sur téléphone ; sur l'émulateur : compteur arrêté à 31/65 au lieu de 65/65, 34 images en échec).
- **Un échec d'image ne fait pas échouer la synchronisation.** `syncImages` relève les images
  qu'un article garde manquantes (timeout, 404…) dans `imageFailures`, une entrée par URL
  (`FailedImage` : flux, titre d'article, adresse) ; le pipeline et l'ajout d'un flux passent par
  `syncImagesSafely` (erreur inattendue journalisée, `imagesInterrupted`). `LAST_SYNC_AT` avance dès
  que les articles et le reste sont passés, et `imageFailureNote` ajoute au message final
  « Images non récupérées : 3 images » (le **nombre** seulement) ; le bandeau propose alors
  « Voir le détail », qui ouvre `ImageFailuresDialog` (flux, article, cause et URL de chaque
  image — la liste défile avec son indicateur, une adresse touchée est copiée et la modale le
  confirme sur place, étant une fenêtre à part où le toast de l'application ne se verrait pas) ; le
  bandeau reste alors 5 s à l'écran (12 s pour une session expirée, 4 s sinon). Un
  téléchargement interrompu, lui, n'a pas de détail : « Images non récupérées (téléchargement
  interrompu) ». La projection `articlesPendingImages` porte `feedId`/`feedTitle`/`title` pour
  nommer chaque image.
  **Délai réseau des images : connexion et lecture 5 s, durée totale 20 s** dans `ThumbStore`
  comme dans le client OkHttp posé sur Coil (`PressIAApp`) : une image qui ne répond pas est
  coupée vite, une image qui répond mais descend lentement (5 Mo à six de front) a 20 s en tout,
  et aucun des deux ne retient la passe. **Plafond de taille : 10 Mo** (`MAX_SOURCE_BYTES`) sur les deux chemins —
  `readCapped` pour les miniatures, `imageCapInterceptor` pour Coil (le `Content-Length` manque
  souvent, il compte les octets lus) : les CDN servent des originales de 30 Mo, qui allaient
  jusqu'au délai et s'annonçaient « délai dépassé » au lieu de « image trop lourde ».
  **Les SVG sont pris en charge** (logos de flux, illustrations) : `coil-svg` + `AndroidSVG`
  (dépendances), `SvgDecoder` enregistré dans le `ImageLoader` de `PressIAApp` pour le corps et
  les favicons, et `ThumbStore` rasterise lui-même (`isSvgImage` détecte « <svg » dans les
  premiers octets, `decodeSvg` rend le gabarit via `renderToCanvas`). Sans cela `BitmapFactory`
  rendait `null` et la miniature restait éternellement « image illisible ».
- **Ne jamais** relancer une passe depuis `syncReadLater` : le pipeline en lance déjà une en
  parallèle (`supervisorScope`), et deux passes simultanées lisaient la même liste d'articles et
  téléchargeaient deux fois les mêmes images (constaté : 26 images sur 32 demandées deux fois).
- **La vignette ne se perd pas au retour du serveur.** `upsertArticles` remplace la ligne entière :
  l'`imageUrl` de l'article entrant **écrase** celui qui était en base. Or Feedly ne renvoie son
  champ « visual » que sur les entrées **récentes** : une entrée rejouée sans image effaçait celle
  déjà affichée, et la vignette disparaissait de la liste à chaque synchronisation.
  `mergeIncomingArticle` conserve donc l'`imageUrl` connu quand l'article entrant n'en porte pas
  (`ArticleFlags.imageUrl` le relit — `flagsOf` le ramène dans la requête).
  - Corollaire : `imageCached` ne vaut pas preuve que le fichier existe. `syncImages` compare donc
    les fichiers au disque (`cachedThumbUrls`) et **retélécharge** les vignettes disparues (purge,
    cache vidé à la main, synchro interrompue) — sans cela la vignette ne revenait jamais, l'article
    étant « déjà téléchargé ».
  - `gcThumbs()` supprime les fichiers dont plus aucun article ne porte l'URL : une `imageUrl`
    perdue rendait donc la vignette orpheline — c'est pour cela que la perte de l'`imageUrl` était
    si grave. Il tourne **à la fin de chaque synchro** (`syncDown`, juste après `pruneArticles`) — un
    fichier orphelin naît d'une URL remplacée, d'une passe interrompue ou d'une suppression hors
    des chemins qui purgent eux-mêmes, et sans cette passe il attendait le prochain dépassement de
    quota — plus aux endroits qui suppriment des articles (`pruneArticles` quand il coupe, désabonnement
    d'un compte, `deleteLocalFeed`, `clearCache`). Coût : un listing de dossier et une requête.
- `clearCache()` / `clearImages()` effacent les fichiers **et** remettent `imageCached` à 0 : la
  prochaine sync, quelle qu'elle soit, récupère tout. Ils purgent aussi les HTML extraits via
  Readability (`content = summary`, `fullFetched = 0`) : les articles sont conservés avec leur
  extrait RSS, la prochaine ouverture complète ré-extrait.
- Listes : les 3 `AsyncImage` d'`ArticleRow` passent par `thumbModel()` (fichier local si présent,
  sinon URL paresseuse au scroll — aucun préchargement de liste). **L'écran article aussi**
  (bannière et images du corps), par le même `thumbModel()`. Une URL déjà téléchargée à la synchro
  ne repart donc pas sur le réseau : fichier `ThumbStore` pour `imageUrl` — et pour la première
  image du corps de tout flux sans `enclosure`/`media:*`, puisque `LocalRssFetcher` prend alors la
  première `<img>` de la description (la miniature **est** la première image de l'article) —, cache
  disque Coil pour les autres. Vérifié en direct : une requête par image à la synchro, **zéro** à
  l'ouverture. Reste : la visionneuse (appui) garde l'URL d'origine, c'est là qu'on récupère la
  pleine qualité.
- `isSameImage` compare les noms de fichier **après nettoyage des variantes CDN**
  (`…-1024x495.webp`, `…__800_800__overflow.jpg`) : sans ça la vignette du flux (`imageUrl`)
  s'affichait en plus de la même image déjà présente dans le contenu (Les Numériques, Next).

## Réglages et préférences

- Réglage ajouté, modifié ou supprimé = cycle complet obligatoire : clé dans `Prefs`, état +
  `reloadFromPrefs`/`save()` dans l'écran Réglages, entrée dans `Prefs.ENTRIES` (sinon absent de
  l'export/import — test `entrees de preferences` à étendre), valeur par défaut cohérente partout
  où elle est lue.
- Les sections sont posées par `SettingsSection(titre)` (`ExtraScreens.kt`, filet + titre coloré) —
  un nouveau réglage rejoint la section de son usage.
- **Chaque réglage est encadré** (`SettingBox`, fin liseré `outlineVariant`) avec son libellé, son
  option et son explication : la page est une longue suite de lignes, sans bordure un interrupteur
  se lisait avec le paragraphe voisin. Une note qui vaut pour toute la section (volume et
  fréquence) reste **hors** encadré, juste sous le titre de section.
- La page est en deux blocs cohérents : **Listes d'articles** (vue, bouton de raccourci,
  glissements de ligne, favicon lu/non-lu) et **Dans un article** (ouverture, Readability,
  double-tap, balayages, icônes d'action, vidéos). Un réglage qui agit sur l'écran article va dans
  le second.
- Les listes d'action (bouton de raccourci, glissements, double-tap, balayages) proposent
  **Recherche** : l'action ouvre l'écran Recherche sur le flux de l'article. « Icônes d'action »
  l'ajoute par défaut en tête (`ARTICLE_ACTIONS_DEFAULT`), et `migrateArticleActions` la donne aux
  configurations restées à l'ancien défaut.
- **Balayages haut et bas dans un article** (`Prefs.ARTICLE_SWIPE_UP` / `ARTICLE_SWIPE_DOWN`,
  `detectArticleVerticalSwipe`) : le geste n'est pris **qu'au bord** de l'article — vers le haut
  une fois le bas atteint (défaut : article plus ancien), vers le bas une fois le haut atteint
  (défaut : article plus récent) ; ailleurs il fait défiler, et un doigt sur un lecteur lui
  appartient (même exception que l'horizontale). Le détecteur est posé **après** `verticalScroll`
  dans la chaîne de modificateurs : dans le passage montant, il voit l'évènement avant le
  défilement et peut lui prendre la course au bord. Sans voisin dans ce sens, un message le dit et
  l'on **revient à la liste**, comme pour le balayage horizontal. Comme les balayages
  gauche/droite, chaque sens interdit la navigation inverse de l'autre (« newer » n'est pas
  proposé en haut, « older » pas en bas).

## Pièges connus — réseau, comptes et synchronisation

- Les champs de config OAuth (`FEEDLY_CLIENT_ID`, `INOREADER_APP_ID`, …) sont lus dans
  `local.properties` ou l'environnement. **Feedly n'en a pas besoin** : sa connexion passe par la
  page officielle intégrée (`FeedlySignInScreen`, identifiants saisis sur feedly.com, jeton de
  session récupéré automatiquement) — ne pas conclure qu'un compte distant ne peut pas
  s'authentifier sous prétexte que ces champs sont vides.
- **Identifiants d'application Inoreader (`AppId` / `AppKey`) : ce n'est pas un OAuth, c'est
  l'« authentification applicative »**, exigée par l'API sur *chaque* appel, à côté de
  l'authentification utilisateur (403 sinon). Par défaut ce sont ceux de l'extension officielle du
  navigateur (`build.gradle.kts`) : `1000001524` / `uxILTlPQilTphfcf4xwBEspL_0EDaUZL` — publics
  par construction, identiques dans tous les bundles de l'extension, et suffisants pour l'API.
  `local.properties` ou l'environnement les remplacent (application déclarée par le développeur).
  Ils sont publiés dans `InoreaderAppAuth` et injectés en en-tête par l'intercepteur du client
  Retrofit (`CloudApis`), **omis quand ils manquent** (le refus serait le même).
  - Conséquence : le flux **OAuth** Inoreader exige, lui, une application *à soi* (URL de
    redirection `pressia://oauth/inoreader` déclarée) — l'identifiant par défaut n'a pas notre
    redirection, Inoreader refuserait le retour. D'où les champs de l'écran Comptes qui démarrent
    **vides** : c'est à l'utilisateur de saisir les siens.
- **Inoreader : connexion « par session »** (`InoreaderSignInScreen`, comme Feedly) : la page
  `www.inoreader.com` s'ouvre dans une WebView, l'utilisateur s'y connecte, et l'en-tête `Cookie`
  **que le navigateur enverrait à l'API** est lu (`CookieManager.getCookie(<url API>)` : seuls les
  cookies du domaine et du chemin, `HttpOnly` compris — `document.cookie` ne les montre pas).
  C'est le mécanisme de l'extension officielle Inoreader pour navigateur, transposé en application
  native ; la connexion est **automatique** dès que le cookie apparaît (sonde de 1,2 s, essai
  au plus toutes les 4 s), les boutons « Je suis connecté » / « Réessayer » ne font que forcer.
  - Le cookie **porteur est `al`** (domaine `.www.inoreader.com`, `HttpOnly`) — pas `SID`.
    Vérifié en direct : `al` seul authentifie `reader/api/0/user-info` (200), `ssid` seul est
    refusé (401). `ssid` est un cookie de **session** (non persistant) : c'est le texte de l'en-tête
    stocké dans le compte qui garde la session utilisable après fermeture de l'application ; il
    finit par expirer, d'où la reconnexion.
  - L'API est appelée **comme l'extension le fait** : en-tête `Cookie` complet, `AppId`/`AppKey` à
    côté (intercepteur), **sans** en-tête `Authorization`. `inoreaderAuth` (pur, testé) classe le
    jeton du compte : `InoreaderAuth.WebCookie` (préfixe `cookie:`, ou forme d'un en-tête `Cookie`)
    → `Cookie: al=…`, `InoreaderAuth.OAuth` → `Authorization: GoogleLogin auth=…`.
    `InoreaderProvider.headers` fait la traduction et `InoreaderService` prend un `@HeaderMap` (les
    deux formes passent par le même client).
  - Candidats : `inoreaderCookieCandidates` (pur, testé) rend l'en-tête complet puis la vue réduite
    aux cookies de session (`al`, `ssid`, `bsid`), `al` en tête — Inoreader compte les requêtes au
    quota. La validation se fait par un **vrai appel API** (`user-info`), jamais sur une simple
    ressemblance. `inoreaderCookieHasSession` évite de lancer un appel (compté) tant que le bac ne
    porte aucun cookie de session. Le « connexion bloquée ? » ne se déclenche que si le
    **formulaire de connexion a disparu** (`input[type=password]`) sans cookie lisible : sinon il
    s'agirait d'un utilisateur en train de saisir ses identifiants. Le flux OAuth reste disponible
    (Comptes → « Connexion OAuth Inoreader »).
  - **Identifiants employés, affichables et remplaçables** (Comptes → « Identifiants Inoreader » →
    `InoreaderIdsDialog`) : App ID, App Key et l'AUTH du compte connecté — `Authorization:
    GoogleLogin auth=…` pour un compte OAuth, `Cookie: al=…` pour une session web (le libellé du
    champ et l'en-tête affiché suivent la forme reconnue par `inoreaderAuth`). Les valeurs secrètes
    sont **masquées par défaut** (bouton œil). Un identifiant
    égal à la valeur par défaut est stocké **vide** : la valeur par défaut reste ainsi vivante.
    `PressRepository.replaceAccountToken` remplace le jeton **en place** — le compte garde son
    `id`, donc ses dossiers, ses abonnements et ses articles — et **refuse** un jeton que le
    service n'accepte pas ou qui appartient à un autre utilisateur.
  - **Renouvellement du jeton OAuth** : le jeton d'accès expire vite (de l'ordre de l'heure chez
    Inoreader). `OAuth.inoreaderTokens` récupère les **deux** jetons (accès + renouvellement) ; le
    second accompagne le compte dans le registre (`CloudAccount.refreshToken`, sérialisé avec le
    compte — donc couvert par `SECRET_PREFS`). `InoreaderProvider.call` enveloppe chaque appel
    API : sur 401, il renouvelle **une seule fois** et rejoue l'appel ; sans cela le compte serait
    déclaré « session expirée » alors qu'un simple renouvellement le ranime.
    `PressRepository.renewInoreaderToken` fait le renouvellement (il est le seul à savoir où lire
    et réécrire le jeton de renouvellement) et **conserve l'identité du compte** : ses dossiers et
    ses articles suivent. Un `CancellationException` n'est **jamais** confondu avec un jeton à
    renouveler.
  - **Le jeton CSRF (`api.token`) vaut pour la session** : `InoreaderProvider.tag` le demande une
    fois par lot (`markRead` / `markUnread`), pas par article — sinon chaque article coûtait deux
    requêtes (GET token + POST edit-tag).
- **Tables JSON de DataStore** (sessions expirées, empreintes « read later », totaux de
  réconciliation) : lecture-modification-écriture, donc **jamais sans verrou** (`expiredMutex`,
  `hashMutex`). Les comptes se synchronisent en parallèle : sans lui, deux écritures qui se
  chevauchent perdent la première (préférence disparue).
- File montante : `pendingSync()` est une **page** (`LIMIT 100`), pas la file. Tout code qui
  raisonne sur « les actions en attente » doit passer par une requête dédiée (`pendingReadIds`,
  `pendingMarkAllCount`, `pendingSavedIds`, `pendingUnsavedIds`) ; `pushQueue` enchaîne les pages
  **jusqu'à ce que la file soit vide** (pas de plafond de pages : chaque tour retire au moins une
  ligne, sinon il s'arrête). Ne jamais vider la file sur un état initial de `provider`
  (`drop(1)`), et une ligne sans flux vaut « tout le compte », pas « à jeter » — `pushPage` la
  pousse à **tous** les comptes cloud (branche « markAllRead », cibles données par
  `markAllReadTargets`, fonction pure testée), le premier ne servant qu'à porter le groupe
  (`accountFor`) : la rattacher au seul premier laissait les articles des autres non lus côté
  serveur, rouverts par la réconciliation suivante.
- `pushQueue` passe par `pushMutex` — pas `syncMutex`, non réentrant, que `syncPipeline` détient
  déjà quand il appelle la file. Sans ce verrou, deux appels qui se chevauchent (lecture d'un
  article et pipeline, par exemple) relisaient la même page avant qu'elle ne se vide et poussaient
  deux fois les mêmes lignes.
- **Marquage groupé article par article : barre de progression en bas** (`BulkProgress`,
  `PressRepository.bulkProgress`). À partir de `PUSH_BAR_MIN` = 10 lignes,
  `markArticlesRead` / `markArticlesUnread` passent par `pushWithProgress`, qui publie
  `(done, total)` après chaque page (`pushQueue(onProgress)`) et remet le flux à `null` dans un
  `finally` — c'est ce qui couvre « tout marquer lu » replié à l'unité (compte sans marquage de
  masse, favoris exclus) et son annulation. En deçà de 10, rien ne s'affiche (la barre
  clignoterait pour deux ou trois articles).
  **Aucun appel réseau en plus** : la page de file reste à 100 lignes et un envoi groupé par
  page (`markRead` / `markUnread` acceptent une liste) — la découper pour faire avancer la barre
  multiplierait les appels. **L'avancement suit chaque envoi réel** : `pushPage` reçoit
  `onDropped` et le rappelle après chaque lot (un groupe **par compte**, donc une étape par
  agrégateur concerné ; aussi après chaque reprise à l'unité). Une sélection qui couvre Feedly
  et Inoreader avance donc en deux étapes, sans requête supplémentaire.
  **Deux opérations qui se chevauchent** (marquage puis « Annuler » pendant la poussée) ne font
  qu'une barre : `bulkBarUsers` / `bulkBarTotal` / `bulkBarDone` sont partagés, la seconde
  **ajoute son total** sans remettre l'avancement à zéro, et `report` transmet le **lot** parti
  (pas un cumul par appel) pour que les deux poussées s'additionnent. Vérifié : `0/82` →
  `0/164` → `82/164` → `164/164`, au lieu de « se vide puis reste pleine ». La barre ne se ferme
  qu'à la dernière opération.
  **L'annulation annule les envois pas encore partis** : à chaque page, `undoneReadRows` retire
  de la file les « read » encore en attente qui ont un « unread » plus récent (l'Annuler), avec
  cet « unread » — le serveur n'a jamais vu le lu, rien n'est envoyé. Ce qui était déjà parti
  garde son « unread » et sera corrigé. Un « unread » antérieur au « read » ne défait rien
  (marqué non lu puis lu : les deux partent dans l'ordre). La page en cours d'envoi au moment du
  tap ne peut pas être stoppée (elle est déjà sur le réseau). Mesuré sur 136 articles : 100 lus
  partis, 36 lus + 36 non-lus retirés, 100 non-lus envoyés (200 lignes au lieu de 272).
  `MainActivity` anime le compteur (`animateIntAsState`, 400 ms) : le nombre rejoint la valeur
  réelle sans la dépasser. Le total peut inclure d'autres lignes déjà en file, d'où
  `done.coerceAtMost(total)`.
- Marquage de masse Feedly : `POST /v3/markers` accepte `type":"feeds"` (ou `"categories"`, avec ou
  sans `asOf`) puis les **ignore** — HTTP 200, corps vide, `markers/counts` inchangé (vérifié en
  direct). Seul `type":"entries"` marque vraiment : `FeedlyProvider.markAllRead` /
  `markAllReadGlobal` énumèrent donc les non-lus du flux (`streams/ids`, paquets de 100
  identifiants), **jusqu'à épuisement** : la pagination suit `continuation` sans plafond, un
  flux ou un compte à plus de 5000 non-lus laissait sinon les plus anciens non lus côté serveur,
  rouverts par la réconciliation suivante. Bonus : ça rattrape les articles que le serveur croit
  encore non lus alors que l'app les a marqués lus, que la réconciliation suivante rouvrirait
  sinon. Ne pas « optimiser » ce chemin en un appel de masse.
- Feedly, identifiant de compte : `cachedUserId` le met en cache **par jeton**
  (`ConcurrentHashMap`), pas dans un champ unique — plusieurs comptes connectés se seraient
  servi le flux global l'un de l'autre. `userId(token)` (connexion) reste sans cache.
- Marquage de masse Inoreader : **pas** `mark-all-as-read`. Ce bouton du site (`POST
  reader/api/0/mark-all-as-read`, `s`, `ts` en secondes, `T`) marque tout ce qui est plus ancien
  que `ts` — **omettre `ts` ou le mettre à 0 revient à maintenant** — et il avance
  `firstitemmsec` du flux. Or l'API **ne rouvre que les articles plus récents que
  `firstitemmsec`** : pour un article plus ancien, `edit-tag` avec `r=read` répond `OK` et
  **n'applique rien**, sans le dire (vérifié sur un même flux, deux articles de part et d'autre
  de la borne : celui d'avant garde son étiquette lue, celui d'après la perd et reçoit `fresh`).
  L'app n'emploie donc pas `mark-all-as-read` : elle énumère les non-lus (`stream/items/ids`
  avec `xt` = étiquette lue, pagination suivie jusqu'à épuisement) puis pose l'étiquette lue par
  `edit-tag` — `i` est **répétable**, tout un lot de la file (100) part en un appel, la borne ne
  bouge pas, et « Annuler » repart exactement d'où l'on était (vérifié : 5 → 0 → 5 et 20 → 0 →
  20). Limite qui reste, la même que sur le site : un article déjà plus ancien que
  `firstitemmsec` (après un « tout comme lu » fait ailleurs) ne se rouvre pas.
  Un dossier est marqué flux par flux par l'appelant ; un compte connecté par cookie porte un
  jeton CSRF vide, et l'API l'accepte.
- **L'étoile d'un article Inoreader est son « Read later »** : l'étiquette
  `user/-/state/com.google/starred` — le flux qu'elle désigne s'intitule « Read later » (vérifié
  en direct ; le flux `saved`, lui, est le « Reading List » des pages web enregistrées, autre
  chose). `InoreaderProvider.markSaved` / `markUnsaved` la posent et la retirent par `edit-tag`,
  et `readLater` / `readLaterIds` la redescendent comme `tag/global.saved` chez Feedly.
  `providerUsesSaved` (pure, testée) dit quels services suivent ce chemin : `setStarred` et
  `PressVM.starState` s'y réfèrent — ne pas remettre « feedly » en dur. Les favoris des flux
  locaux restent locaux. Chez **FreshRSS**, la même étiquette `starred` est sa liste de
  **favoris** (son site ne parle pas de « Read later ») : même chemin, jeu `later_fr` et libellé
  « Favoris - FreshRSS » à part.
  **Le service d'un tableau est rangé avec l'article** (`articles.laterProvider`, colonne ajoutée
  par `MIGRATION_8_9`) : c'est ce qui sépare les filtres « À lire plus tard - Feedly » et
  « - Inoreader » des Favoris, y compris pour les articles d'un flux **désabonné** — filtrer sur
  `subscriptions` en laissait 15 sur 19 dans « tous seulement ». La provenance est posée à
  l'étoile (`setStarred`) et à chaque import de tableau (`syncReadLater`), préservée par
  `mergeIncomingArticle` ; l'empreinte du tableau (`savedHash`) porte le **service**, ce qui force
  une réimportation unique après la migration 8→9 (sans elle, un tableau « inchangé » ne se
  réattribuait jamais).
  **Supprimer le compte efface ce marquage** (`deleteAccount` → `clearLaterOf(providerBase(id))`) :
  un article d'un flux encore abonné (autre compte, flux local) gardait sinon le drapeau du
  service supprimé — visible dans « Tous les favoris » alors que le jeu du service avait disparu
  du sélecteur (`favSetAvailable`, faute de compte). Les articles qui n'étaient là que pour ce
  tableau s'en vont ensuite avec `deleteOrphanArticles`. L'étoile PressIA, elle, reste : c'est un
  état local, sans compte.
- **Feedly marque lu tout seul au-delà de 31 jours** : son état lu/non-lu ne dit plus ce que
  l'utilisateur a lu passé ce délai. `autoReadAgeMs("feedly")` = `FEEDLY_AUTO_READ_AGE_MS`, et
  `autoReadCutoff` borne **deux** chemins : `storeArticles(..., keepReadBeforeMs = …)` préserve
  l'état local des articles plus vieux déjà connus (`mergeIncomingArticle` → `beyondAutoRead`), et
  `reconcileReadState` ne ferme que les non-lus de la fenêtre (`closableUnread`). Sans ces bornes,
  chaque synchronisation refermait les vieux non-lus balayés par le service. Un article **inconnu**
  garde l'état annoncé par le serveur : il n'y a rien de local à préserver.
  La borne se coupe depuis Réglages (« Feedly : garder non lu au-delà de 31 jours », activée par
  défaut) : `PressRepository.cutoffFor(providerId)` rend alors `null`, et l'état du service fait
  foi — les six appels à `autoReadCutoff` passent par lui, un nouveau doit le faire aussi.
- Bandeau de synchro : l'avancement par flux d'un compte (découverte, puis rattrapage) vient de
  `provisionHistory(onFeed)`, relayé par `syncAccount(..., onFeed)` depuis `syncDown`. Le total est
  celui de la phase en cours — les flux en retard ne sont connus qu'après la découverte : le
  compteur est croissant et finit sur le vrai total, jamais sur le nombre d'abonnements.
- **Pas de planificateur de synchro** (le Worker a été supprimé : elle ne tourne qu'app ouverte) :
  `MainActivity` appelle `vm.syncOnLaunch()` à **chaque `ON_RESUME`**, pas seulement à la création
  de l'activité. Un déclenchement au seul `onCreate` laissait une application rouverte après des
  heures d'arrière-plan (processus vivant) sans aucune synchro, « Dernière synchro » figé à la
  veille malgré un réglage à dix minutes. C'est `syncOnLaunch` qui arbitre : option, sources et
  fenêtre de fraîcheur (`isSyncFresh`, réglage « Fréquence de synchronisation », 0 = manuelle =
  jamais frais), et il s'abstient si un travail est déjà en cours (le relancer l'annulerait).
  La **minuterie de premier plan** (`PressVM.startSyncTicker`/`stopSyncTicker`, câblés sur
  `ON_RESUME`/`ON_PAUSE`) prend le relais tant que l'écran reste visible : elle dort jusqu'à
  l'échéance du même réglage, repasse par `syncOnLaunch`, puis attend la fin de la passe
  (`syncJob?.join()` — la date n'est reculée qu'à la fin, sinon elle repartait aussitôt). Fréquence
  « manuelle » (0) : la boucle veille toujours (une fois par minute), sans quoi repasser à une
  fréquence depuis Réglages ne redémarrerait jamais la minuterie — aucun `ON_RESUME` sur cet écran.
  `attemptedAt` (mémoire) évite de retenter chaque minute une synchro en échec, `LAST_SYNC_AT`
  n'avançant qu'en cas de succès.

## Ajouter un agrégateur (fournisseur cloud)

Un agrégateur se branche par son **service** (`"feedly"`, `"inoreader"`), jamais par son nom : une
clé de compte vaut `"<service>#<empreinte du jeton>"` et `providerBase` la ramène au service.
Liste complète des points à toucher — aucun n'est facultatif si le compte doit se comporter comme
les autres (chaque point a coûté un bug quand il manquait).

1. **API** (`data/remote/CloudApis.kt`) : les interfaces Retrofit du service et leurs modèles
   `@Serializable` (`ignoreUnknownKeys = true`), plus un `companion.create()` si le service exige
   des en-têtes propres (l'« authentification applicative » d'Inoreader). Une réponse en **texte**
   (`OK`, jeton CSRF) se type `Unit` ou `ResponseBody`, **jamais** `String` : le convertisseur
   JSON lève une `JsonDecodingException` et la ligne de file reste bloquée alors que le serveur a
   agi (vécu sur `edit-tag`).
2. **Fournisseur** (`data/remote/Providers.kt`) : une classe `FeedProvider`. Obligatoire : `id`,
   `categories`, `subscriptions`, `stream`, `markRead`, `markUnread`, `markAllRead`. Tout le reste
   a un défaut qu'il faut lever **délibérément** :
   - `markAllReadGlobal` + `bulkMarkAllRead = true` : sans eux, « tout marquer lu » retombe à
     l'unité ; un marquage de masse **accepté sans effet** (HTTP 200) rouvre tout à la synchro
     suivante (Feedly `type:"feeds"`, Inoreader `mark-all-as-read` qui avance `firstitemmsec`) ;
   - `markSaved` / `markUnsaved` + `readLater` / `readLaterIds` : sinon l'étoile reste locale ;
   - `unreadIds`, `unreadTotal`, `unreadCountsByFeed` : réconciliation distante, rattrapage des
     flux en retard, détection de changement ;
   - `profile` + `userId` : libellé du compte et **anti-doublon** à la reconnexion (sans
     `userId`, chaque reconnexion crée un second compte) ;
   - `verifyWebSession` si la session se capture dans une WebView (cookie) ;
   - `search` : facultatif et **inutile** — la recherche de l'app est locale, par choix.
   Préférer l'énumération d'identifiants + un tag par lots (`i` répétable chez Inoreader) au
   marqueur de masse du site : c'est ce qui rend « Annuler » réel.
3. **Enregistrement** : `providerFor(service)` (`PressRepository`) pour l'instance et
   `serviceLabel(service)` (`Accounts.kt`) pour le libellé — sans ça, « Compte » partout.
4. **Règles pures du service** (`data/remote/FeedProvider.kt`, testées en JVM) : `autoReadAgeMs`
   si le service marque lu de lui-même (Feedly, 31 j — consulté par `cutoffFor` à la synchro) et
   `providerUsesSaved` si son étoile est son « à lire plus tard » — consulté par `setStarred` et
   `starState`. Tester les deux.
5. **Connexion** : un écran (page officielle intégrée, comme `FeedlySignInScreen` /
   `InoreaderSignInScreen`), sa route dans `MainActivity` (et dans `UTILITY_ROUTES`, sinon le
   retour remonte tout l'empilement), une entrée dans `LoginScreen`, `expiredReconnectRoute`
   (`SessionExpired.kt`) pour le raccourci « Reconnecter », et
   `PressVM.connectAccount(service, token, name, refreshToken)` →
   `PressRepository.addAccount` (profil, `userId`, fusion du compte existant, compte actif).
   - **OAuth** : `OAuth.fooAuthUrl` / `fooToken` / `fooRefresh` (`data/remote/OAuth.kt`), retour
     `pressia://oauth` (`MainActivity` + manifeste), `CloudAccount.refreshToken` pour la durée, et
     le champ `renewToken` du fournisseur câblé par le dépôt (bloc `init` de `PressRepository`,
     `renewInoreaderToken` sert de modèle : il garde l'identité du compte, donc dossiers et
     articles).
6. **Clés du service** : `Prefs` (`INOREADER_APP_ID`…), `Prefs.ENTRIES` (export/import) et
   `SECRET_PREFS` (`Backup.kt`) pour tout ce qui est secret ; étendre le test « entrées de
   préférences ». Si l'API les exige à chaque appel, les publier dans un objet lu par
   l'intercepteur Retrofit (`InoreaderAppAuth` + `PressRepository.refreshInoreaderAppAuth`) plutôt
   que de relire DataStore à chaque requête.
7. **Réglages propres au service** : clé dans `Prefs`, état + `save()` dans l'écran Réglages,
   entrée dans `ENTRIES`, valeur par défaut lue au même endroit partout (`cutoffFor` pour
   « Feedly : garder non lu au-delà de 31 jours »).
8. **Favoris** : si le service a un tableau « à lire plus tard », sa provenance est écrite sur
   l'article (`ArticleEntity.laterProvider`) à l'étoile (`setStarred`) et à l'import
   (`syncReadLater`), avec un jeu `favScopeArg` + un libellé `favSetLabel` + une ligne dans
   `FavorisScreen` conditionnée par `PressVM.hasAccount(service)`. Attention : un tableau
   inchangé ne rejoue **pas** l'import (empreinte `savedHash`) — d'où le suffixe de service dans
   cette empreinte, qui force la réimportation une fois et pose la provenance sur les lignes
   existantes.
9. **Textes** : `ABOUT_TOPICS` (« Comptes », « Favoris et Read later », « Ce qui reste non lu »…)
   et `Changelog.kt`. Les règles annoncées doivent être **vérifiées en direct** : le site et l'API
   ne se racontent pas pareil (`saved` renvoie le flux principal, `starred` est le « Read later »).
10. **Vérification** : connexion réelle, synchro (dossiers, abonnements, articles, miniatures),
    lu/non-lu poussé, réconciliation, marquage de masse **et son annulation**, tableau « à lire
    plus tard » dans les deux sens, déconnexion (aucune trace restante), et session expirée
    (bandeau + « Reconnecter »). Les tests unitaires ne couvrent que le pur : le reste se voit à
    l'écran.

### FreshRSS (implémenté, exemple complet)

FreshRSS est **auto-hébergé** et expose l'**API Google Reader** sous
`<serveur>/api/greader.php/` : mêmes endpoints qu'Inoreader, d'où un fournisseur qui **hérite**
d'`InoreaderProvider` (`FreshRssProvider`, `data/remote/Providers.kt`) et ne remplace que trois
choses :

- **le serveur**, que porte le jeton du compte — `freshrss|<serveur>|<auth>`
  (`data/remote/FreshRss.kt`, pur et testé : `freshRssBaseUrl`, `freshRssToken`, `freshrssAuth`) —
  plutôt qu'une colonne de plus dans le registre des comptes ; un service Retrofit par serveur, en
  cache (`InoreaderService.create(base, appAuth = false)` : pas d'`AppId`/`AppKey` hors Inoreader) ;
- **l'authentification** : identifiant + mot de passe → `accounts/ClientLogin` rend `Auth=…`,
  envoyé en `Authorization: GoogleLogin auth=…`. La connexion n'est retenue qu'après un vrai appel
  `user-info`. C'est le seul agrégateur qui **saisit le mot de passe** : la page de connexion
  intégrée n'existe pas, et le formulaire vit dans `LoginScreen` (adresse, identifiant, mot de
  passe → `PressVM.connectFreshRss`). Le mot de passe n'est pas conservé, seul le jeton. Le schéma
  est ajouté quand il manque (`https://` par défaut) : **un serveur en HTTP simple doit être saisi
  avec `http://`**, sinon la poignée de main TLS échoue et la connexion est refusée ;
- **le chemin du flux** : FreshRSS découpe le chemin sur les barres, l'identifiant d'un flux doit
  donc y arriver **encodé** (`feed%2Fhttp%3A%2F%2F…`), ce que fait `streamPath` (Inoreader, lui,
  accepte la forme brute — vérifié en direct, ne pas changer ce comportement).

L'étoile PressIA est l'étiquette `starred` de FreshRSS, sa liste de **favoris** :
`providerUsesSaved("freshrss")` est vrai, l'import du tableau pose `laterProvider = "freshrss"`, et
l'écran Favoris a son propre jeu (`later_fr`, « Favoris - FreshRSS » — libellé propre, le site ne
parle pas de « Read later »). Rien d'autre n'est propre au service : dossiers, abonnements,
articles, lu/non-lu (marquage de masse réversible par `edit-tag`, jeton CSRF compris), compteurs,
profil, `userId` et réconciliation viennent d'Inoreader. La session expirée renvoie vers Comptes
(`expiredReconnectRoute`, cas par défaut), seul écran qui sait ressaisir le mot de passe.

Vérifié sur un faux serveur Google Reader (`10.0.2.2:8898`, script jetable) : connexion, dossiers
et abonnements, articles, lu poussé, étoile dans les deux sens, marquage de masse, session expirée
(401 → mention sur le compte, levée dès un appel réussi), déconnexion sans trace (abonnements,
articles et favoris du compte compris).

## Pièges connus — données, cache et stockage

- Pas de migration Room à écrire pour une simple requête DAO ou colonne déjà existante ;
  `exportSchema = false`, version DB actuelle : 9 (provenance du « Read later », `MIGRATION_8_9`).
- **Un ajout d'`Index` sur une entité exige une migration** (Room compare le schéma et son
  empreinte à l'ouverture : sans `MIGRATION_6_7`, l'app plantait sur une base v6 existante) :
  `CREATE INDEX IF NOT EXISTS` sur les trois index des stats (`articles.readAt`,
  `reading_events.articleId`, `reading_events.openedAt`). « Passés en lu sans ouvrir » cherche un
  évènement par article lu : sans l'index sur `articleId`, la sous-requête balayait toute la table
  d'évènements une fois par article.
- **Un HTML d'article est plafonné à l'écriture** (`MAX_ARTICLE_HTML`, 300 000 caractères) :
  corps du flux dans `storeArticles`, extraction dans `ensureFull` (`capArticleHtml`). Android lit
  SQLite par fenêtre de curseur (~2 Mo) : une **seule** ligne plus grosse rend la requête
  illisible — constaté en release sur la liste des articles, « Couldn't read row 54, col 0 from
  CursorWindow », l'application se fermait. `MIGRATION_7_8` coupe une fois les corps déjà en base
  (et remet `fullFetched = 0`, l'article sera ré-extrait à l'ouverture).
- **« Durée d'historique hors favoris » est une borne de rétention**, pas seulement la fenêtre
  remontée à la synchronisation : `pruneArticles()` (fin de `syncDown`, ancien `trimToMax`)
  supprime à chaque passe les articles plus vieux que la fenêtre, selon la règle pure
  `beyondHistory` (favoris, « Read later » et dates inconnues exceptés), que
  `dao.expiredArticleIds` reprend en SQL. Avant, `withinHistory` exemptait les articles déjà
  connus : un article en base survivait indéfiniment à la fenêtre et ne partait qu'au quota par
  flux. Le quota (`MAX_ITEMS`) reste le second garde-fou.
- **L'écran article ne lit plus d'articles pour son balayage** : `PressVM.scanIds`
  (`dao.streamIds` → `SELECT id …`) donne les voisins. `SELECT *` chargeait 2000 articles complets,
  HTML compris, à chaque rafraîchissement, pour n'en garder que les identifiants. **Chaque écran à
  liste propre fournit la sienne** (`PressVM.openArticleWith` → `takeArticleNeighbours`, consommée
  une fois) : Favoris, Recherche (ses résultats) et Doublons (les groupes affichés, à plat) — sans
  elle, un article absent du périmètre du flux n'avait aucun voisin et le geste revenait en arrière
  au lieu de passer au suivant. Côté écran article, la liste est
  `remember` **sans clé** : le balayage change d'article sans changer d'écran, une liste
  re-dérivée par article repartirait du périmètre du flux au premier swipe.
  `scanIds` ne porte que `KEEP_MAX` (400) identifiants : en approchant du bout, l'écran demande la
  suite (`PressVM.moreScanIds`, 400 de plus, toujours des identifiants seuls), aussi loin que le
  périmètre va — sinon le geste annonçait « pas d'article plus ancien » alors que la liste en
  affichait d'autres. Une liste fournie par l'écran appelant n'est jamais prolongée.
  **La liste, elle, est paginée** (`STREAM_PAGE` = 200, `PressVM.loadMoreStream` appelé par
  `ArticleList.onEndReached` sur le flux principal comme sur les Favoris) : plus de plafond à
  2000 articles chargés d'un coup. `loadMoreStream` ne grandit que si les lignes arrivées égalent
  la page demandée — un appel tardif (avant que la page n'arrive) est ignoré, et une page
  incomplète veut dire « fin du périmètre ». `switchScope`, le filtre non-lus et le jeu de
  favoris remettent la pagination à zéro.
- Readability : `content` porte **soit** la version du flux, **soit** l'extraction — `fullFetched`
  dit laquelle. La requête est neutre : navigateur mobile en `User-Agent`, `Accept` et
  `Accept-Language`, **pas de `Referer`** (un `https://www.google.com/` traînait depuis le
  premier commit, sans motif — un référent menteur n'aide aucun site et peut en faire refuser
  d'autres). Couper le mode (icône, pastille, double-tap) doit **réécrire** `content` avec la
  version du flux et remettre `fullFetched` à 0 : sans cette écriture la copie Readability restait
  en base et l'article rouvert reprenait la version extraite. Avant d'écraser `content` par une
  extraction, `saveExtractedContent` recopie le HTML du flux dans `summary` quand il est plus riche
  que l'extrait RSS (flux « complets » : Next) — c'est ce que « Revenir à l'extrait RSS » retrouve,
  sinon l'article revenait tronqué au petit extrait. La synchro, elle, réécrit
  `content`/`summary` avec les valeurs du serveur (`upsertArticles` remplace la ligne) :
  l'extraction ne survit pas à une synchro, et c'est la version du flux qui revient — comportement
  voulu.
- **Un échec d'extraction ne remplace plus le texte.** `ensureFull` rend un `FullFetch(html,
  outcome)` (`FullOutcome.OK / BLOCKED / FAILED`) : l'écran n'affiche `html` que sur `OK`, sinon
  il revient à `feedHtml(a)` — la version du flux, jamais le petit `summary` seul — et montre une
  carte qui distingue « site protégé » (anti-robot, abonnement, page JavaScript) de « site
  injoignable ou page illisible ». En ouverture automatique (épingle Readability), l'échec
  relâche l'épingle (`readabilityOn = false`). Côté base, `BLOCKED` ne purge une extraction
  vérolée **que si** `a.fullFetched` : purger un contenu de flux (Next) lui faisait perdre son
  corps au profit du petit extrait.
- `Readability.CONTENT_SELECTORS` porte `#next-single-post` : sur Next le corps est là, alors
  que le conteneur `article` embarque les commentaires (37 810 car. extraits contre 4 811 de vrai
  corps).
- `articleBlocks` ne retient que `p, li, h1..h6, pre, blockquote, figcaption, img, video, iframe,
  table` : certains flux livrent le corps en texte nu (`…texte…<br><img …>`, cas des Numériques).
  Les nœuds texte de premier niveau sont enveloppés dans un `<p>` avant sélection, sinon
  l'article s'affichait sans son texte.
- Flux RSS locaux (`provider = "local"`, identifiant = l'URL) : leur état lu est **purement local**
  (aucun serveur ne le renvoie). `syncDown` appelle donc `storeArticles(…, keepRead = true)` pour
  eux : `upsertArticles` remplace la ligne entière, et sans ce drapeau chaque synchronisation
  repassait tous les articles du flux en non lus. Les comptes cloud gardent l'inverse (l'état
  distant fait foi) — voir `mergeIncomingArticle`, où `unread`/`readAt` ne sont repris que si
  `keepRead`. Toute nouvelle écriture d'articles locaux doit passer par ce chemin.
- `deleteLocalFeed` = articles + miniatures orphelines (`gcThumbs`) + images pleine taille du cache
  Coil (`articleMediaOfFeed` → `htmlImageUrls` sur `content`/`summary` → `purgeImages`) +
  `vacuum()`. Les **traces de lecture restent** (voir la note « statistiques » ci-dessous).
  `gcThumbs` ne couvre que `filesDir/thumbs` ; une image encore réclamée par un autre flux reste en
  cache, et le nettoyage du cache Coil se voit dans le log (`images purgées : n/m`).
- Statistiques = registre conservé : les traces `reading_events`/`extract_events` **ne partent
  plus** avec un flux ni avec un compte (`deleteAccount`, désabonnement en synchro,
  `deleteLocalFeed`) — les effacer remettait les stats à zéro dès qu'on supprimait un compte. Leurs
  lectures s'affichent alors sous le libellé `DELETED_FEED_LABEL` (« Flux supprimé »), faute de
  titre en base. La remise à zéro est **manuelle** (bouton + confirmation sur l'écran Stats →
  `resetStats`) et pose `Prefs.STATS_SINCE`, borne basse appliquée par `statsLowerBound` : elle est
  indispensable au compteur « passés en lu sans ouvrir », déduit de `articles.readAt` (un état
  d'article que la remise à zéro ne touche pas).
- Suppression de masse (compte, flux, remise à zéro des stats) ⇒ `vacuum()` : `VACUUM` puis
  `PRAGMA wal_checkpoint(TRUNCATE)`. Sans le second, le `VACUUM` réécrit la base dans le WAL, le
  fichier principal garde sa taille, le WAL gonfle et « Cache utilisé » **monte** après une
  suppression (mesuré : 1,8 Mo → 120 Ko avec le point de contrôle, contre 1,8 Mo → 1,9 Mo sans).
  « articles » dans le détail du cache = les fichiers `pressia.db*` (db + WAL + shm), c'est pourquoi
  la taille ne redescend pas toute seule quand les articles partent.
- Source d'un article : `articles.feedTitle` (migration 5→6) fige le titre du flux **au moment de
  l'enregistrement**. Les listes lisaient la source dans `subscriptions` ; la ligne disparaissant
  au désabonnement, les favoris d'un flux supprimé perdaient leur provenance. `storeArticles` le
  remplit depuis l'abonnement, `Providers` depuis `origin.title` de l'entrée (couvre le tableau
  « Read later » d'un flux non abonné), `mergeIncomingArticle` le conserve si l'entrée n'en porte
  pas, `ArticleRow` et l'écran Doublons retombent dessus (et `FeedAvatar` sur l'initiale).
  `setFeedTitle` (flux local qui annonce son nom) met aussi à jour les articles du flux.
- **Un dossier vide n'affiche rien, et n'a pas de « tout le compte ».** Les requêtes de liste
  prennent `catEmpty = 1` pour « aucun dossier demandé » : `PressRepository.catScope` ne le pose
  que si `catId == null`, et rend une liste impossible (`listOf("\u0000")`, un identifiant qu'aucune
  URL ni aucun compte ne peut porter) pour un dossier sans flux.
  Avant, les deux cas se confondaient : un dossier vide affichait tous les articles de
  l'application, et « tout marquer lu » y marquait tout le compte (`PressVM.markAllRead` sort
  désormais sans rien faire).
- Articles « collants » de la session (`PressVM.keepIds`) : bornés à `KEEP_MAX` (400, les plus
  récents gardés). La requête les lie un par un (`id IN (:keepIds)`), une session en accumulait
  des milliers.
- **Sauvegarde Google : les jetons partent, le contenu des articles non.** `allowBackup="true"`
  avec `dataExtractionRules` (`res/xml/backup_rules.xml`, Android 12+ et transfert
  téléphone-à-téléphone) et `fullBackupContent` (`res/xml/backup_content.xml`, Android 11-) :
  `files/datastore` (comptes, jetons, réglages), `files/sauvegarde-auto.json` et le profil WebView
  sont sauvegardés ; `databases/pressia.db*` (la base porte le HTML des articles) et
  `files/thumbs/` sont exclus. Ce n'est pas du zèle : le quota est de **25 Mo par application**, et
  un dépassement fait sauter la sauvegarde **entière**, jetons compris. Les miniatures pèsent
  100 Mo et plus sur une base nourrie, la base plusieurs Mo ; le fichier de métadonnées, quelques
  kilo-octets. Les règles ne savent pas exclure une colonne : exclure le HTML, c'est exclure la base.
- **La copie automatique (`data/AutoBackup.kt`) est ce qui remplace la base dans la sauvegarde.**
  `writeAutoBackup()` exporte `Sections.ACCOUNTS` + `Sections.READ` — dossiers et flux **locaux
  seulement** (`exportJson(..., localFeedsOnly = true)` ; un flux de compte y perdrait son dossier,
  les dossiers des comptes n'étant pas exportés), étoiles PressIA, lus des flux locaux, jamais le
  contenu — vers `files/sauvegarde-auto.json`, en
  écriture atomique (temporaire + renommage). Elle est **réécrite au passage en arrière-plan et à la
  fin de chaque synchro** : la sauvegarde Google tourne elle aussi écran éteint, elle trouve donc
  toujours une copie à jour. `restoreAutoBackupIfEmpty()` la réimporte **au début de
  `syncOnLaunch`, avant le test de fraîcheur**, et seulement si **la base est vide**
  (`autoBackupWorthRestoring` : aucun abonnement et aucun favori). Le garde précédent — « aucun
  flux local » — laissait passer une bibliothèque d'un seul compte (Feedly) sans favori :
  réimportée à chaque lancement, elle détachait tous les flux de leurs dossiers (le fichier ne
  porte que les dossiers locaux) et remettait `LAST_SYNC_AT` à zéro, donc « Jamais synchronisé »
  jusqu'à la synchro suivante. Trois autres pièges, tous
  constatés en simulation (base supprimée, fichier conservé) :
  - `LAST_SYNC_AT` **est restauré par Google** (il vit dans DataStore) : sans le remettre à zéro,
    la première synchro est jugée « récente » et sautée — écran vide, et les lus/favoris attendent
    une passe qui ne vient pas. `RECONCILE_TOTALS` et `READLATER_HASHES` sont remis à zéro pour la
    même raison (calculés sur la base qui n'est pas revenue, ils feraient sauter la réconciliation
    et l'import du « Read later »).
  - **Ne pas réécrire la copie juste après l'import** : les articles ne sont pas revenus, écrire
    l'état de la base effacerait les lus/favoris encore en file. Elle est réécrite à la fin de la
    synchro, quand `RESTORED_STARRED` / `RESTORED_READ` ont été rejoués (limite assumée : un
    article sorti du flux entre-temps perd son état).
  - `markStarred` et `existingIds` sont **découpés par paquets de 500** dans la restauration : un
    gros jeu de favoris dépasse la limite de variables SQLite des anciens Android (~999).
- **La sauvegarde de l'application (écran Sauvegarde/Restauration, `Backup.kt`) reste le chemin
  complet** : elle exporte par sections (réglages, stats, comptes, favoris et lus) vers un fichier
  choisi par l'utilisateur, jetons exclus tant qu'il ne coche pas la case. La copie automatique
  ci-dessus n'en est qu'un sous-ensemble silencieux, pour la sauvegarde Google.
  Le fichier porte `backupVersion` (`BACKUP_VERSION`), et **l'import le vérifie**
  (`checkBackupFormat`) : un format plus récent est refusé, sinon une version ancienne de
  l'application restaurerait en silence et en moins (sections et réglages inconnus ignorés).
  Le message nomme les deux versions en présence (`appVersionName` du fichier, `VERSION_NAME`
  courante). Un fichier sans champ vaut la version 1. Le numéro ne s'incrémente que pour un
  changement **incompatible** — tant que le format ne fait que s'ajouter, l'ancien import lit le
  reste.
- **OPML (écran Sauvegarde/Restauration, `data/Opml.kt`)** : import et export des **flux locaux**
  seulement, dossiers compris. Les abonnements d'un compte appartiennent au service, qui sait déjà
  les exporter ; les réimporter ici créerait des flux locaux en double. `parseOpml` retient tout
  `outline` porteur d'un `xmlUrl` (`xmlurl` en minuscules dans de vieux exports), un outline sans
  adresse étant un dossier — et seul le **parent immédiat** compte, PressIA n'ayant qu'un niveau.
  L'import nettoie l'adresse (`sanitizeFeedUrl` + `isSupportedFeedUrl`), dédoublonne par
  `feedUrlKey` sur **tous** les abonnements — l'identifiant d'un abonnement de compte porte son URL
  (`feed/https://…`, la clé en sort identique), donc réimporter l'export d'un service connecté ne
  crée aucun doublon — puis crée les dossiers par `localMirror` et range par `moveFeedsToFolder` ;
  le titre du fichier est gardé. `buildOpml` échappe les valeurs (`&` d'abord), et la lecture est
  plafonnée (`MAX_OPML_BYTES`, 2 Mo).
- **Rappel de sauvegarde, au plus une fois par mois** (`backupReminderDue`, `Backup.kt` ; modale
  « Pense à sauvegarder » de `MainActivity`, posée par `PressVM.maybeRemindBackup` au retour au
  premier plan) : il paraît quand des réglages ont changé depuis la **référence** (dernier export
  ou import ; à défaut l'état de la première ouverture), qu'il y a quelque chose à sauvegarder et
  que le dernier rappel date de plus d'un mois. **L'ancienneté d'une sauvegarde ne rappelle plus
  rien par elle-même**, et une application fraîchement installée n'est jamais rappelée : la
  première ouverture pose l'empreinte de référence sans rien afficher, et le rappel attend une
  modification réelle (au démarrage suivant). Trois clés propres à l'appareil, hors `ENTRIES` et
  dans `NON_EXPORTED_PREFS` : `BACKUP_CHECKPOINT` (date du dernier export ou import, 0 = jamais),
  `BACKUP_CHECKPOINT_HASH` (empreinte de référence des réglages) et `BACKUP_NUDGE_AT` (dernier
  rappel affiché, posé **à l'affichage** : fermer la fenêtre ne le fait pas revenir avant un
  mois). `LAST_BACKUP_AT` reste réservé aux exports — c'est la date affichée, qui ne doit pas
  annoncer une restauration. `markBackupPoint` (fin d'export, fin d'import) pose les deux premiers
  **après** l'écriture de `LAST_BACKUP_AT`/`LAST_BACKUP_META` : ils font partie des réglages
  comparés, les poser avant déclencherait un rappel pour un changement qu'on vient de faire.
  L'empreinte (`backupFingerprint`) est le SHA-256 d'une liste `clé=valeur` triée, restreinte aux
  noms de `ENTRIES` moins `SECRET_PREFS` et `EXPIRED_ACCOUNTS` : un jeton qui change ou une
  session expirée ne déclenchent rien.

## Pièges connus — interface, navigation et gestes

- **`PressVM` : une propriété assignée depuis `init` doit être déclarée avant lui.** Ce qui est
  appelé dans le bloc s'exécute **pendant** la construction : `viewModelScope` est
  `Dispatchers.Main.immediate`, et un corps qui ne suspend pas (DataStore répond sans suspendre
  quand ses préférences sont déjà chargées dans le processus, `MutableStateFlow` avec valeur)
  s'exécute dans la foulée. `unreadOnly`, déclaré bien plus bas, était ainsi affecté alors
  que son délégué `mutableStateOf` était encore nul : plantage au démarrage
  (`NullPointerException` dans `setUnreadOnly`). Deux garde-fous depuis : la propriété est
  déclarée avant `init`, et l'appel du démarrage passe par `launch(Dispatchers.Main)` — **sans**
  `.immediate`, donc planifié après le constructeur. Un plantage qui revient sur ce chemin vient
  d'un APK antérieur au correctif, pas du code : la trace mentionne alors
  `refreshUnreadOnly` → `setUnreadOnly`, enchaînement qui n'existe plus.

- Modifier une vue article = toucher `ArticleRow` dans `Components.kt`. Le `MetaLine` (source +
  étoile + heure) est partagé par les 4 vues ; lui donner un paramètre `modifier` plutôt que d'en
  dupliquer une variante.
- La position du rond lu/non-lu (`dotAtStart`) pilote aussi celle de l'étoile de swipe : les deux
  doivent rester du même côté, sinon l'information saute d'un bout à l'autre de la ligne au geste.
  Il ne vaut que pour la vue **magazine** : en liste, carte et compacte le bouton de raccourci
  reste au bord droit, même sur un article sans image (la place de l'image montre alors le
  favicon du flux — le déplacer à gauche retirait le bouton de droite).
- Tiroir (`MainActivity`) : `gesturesEnabled = drawer.isOpen || when { current == "article" ->
  false; hierarchical -> current != "home"; else -> !(current == "stream" && !atHome) }`.
  Le balayage de bord est coupé là où le geste vers la droite a déjà un sens — article précédent
  sur l'écran article, montée d'un niveau sur l'Accueil et sur une liste de dossier ou de flux
  (`StreamScaffold` / `SubscriptionsScreen`, `onSwipeBack`) — mais **rétabli tiroir ouvert** :
  c'est ce qui permet de le refermer, le `onClose` du scrim Material étant conditionné par
  `gesturesEnabled` (sinon le clic hors du panneau ne fait rien).
- Navigation hiérarchique quand « Tous les articles » est la page d'accueil (`homeRoute ==
  "stream"`, `hierarchical` dans `MainActivity`) : racine (« Tous les articles », `stream` sans
  périmètre) → Accueil (`home`) → dossier/flux (`stream` avec périmètre). Le bouton retour et le
  balayage vers la droite remontent **d'un niveau** (`goHome()` : dossier/flux → Accueil, Accueil →
  racine) ; un article revient sur la liste du flux, comme avant. À la racine, le balayage ouvre le
  tiroir (comme sur les autres écrans d'accueil) : c'est `StreamScaffold.onSwipeBack` qui décide,
  selon `hierarchical && atRoot`. En mode « Accueil » classique, `goHome()` ramène tout à la page
  Accueil, qui est alors le dernier niveau.
- Règle générale du retour : **on remonte vers l'accueil configuré, on ne ferme qu'une fois
  dessus** (`atHome` = racine si `hierarchical`, sinon la page `home`). C'est ce qui rend le
  réglage « Page de démarrage » effectif immédiatement : changer l'accueil depuis Réglages ne
  déplace personne, mais le retour suivant depuis Réglages ramène à la dernière page de contenu
  (`backToLastContent`), puis la page courante n'étant plus l'accueil configuré, le retour suivant
  mène à la **nouvelle** page d'accueil, et c'est seulement là qu'un autre retour ferme
  l'application.
- **Retour depuis un écran utilitaire : la cible se lit dans la pile**, pas dans `lastContent`
  seul. `contentReturnTarget` (pur, testé) prend la première destination de **contenu** située
  sous l'écran courant dans `nav.currentBackStack.value` ; `lastContent` ne sert que de secours
  quand la pile n'en contient aucune. Lire `lastContent` seul rouvrait l'article : ouvert depuis
  Favoris puis quitté par le retour, il restait la « dernière page de contenu » et le retour
  suivant tentait d'y dépiler, échouait, puis le **recréait** (`nav.navigate`). Ouvrir Favoris
  depuis un article y ramène toujours : l'article est alors réellement sous l'utilitaire.
- **Chaque entrée de navigation « article » porte son propre article** (`ARTICLE_ID` rangé dans
  `entry.savedStateHandle`, relu par `getStateFlow`). Une valeur globale (`selectedArticleId`,
  supprimée) faisait afficher, en revenant sur un article antérieur après un détour par Favoris,
  celui qu'on venait d'ouvrir depuis Favoris : la destination restaurée était la bonne, mais
  l'identifiant relu ne l'était plus. Le balayage horizontal ne change pas d'entrée : il met à
  jour cet état via `ArticleScreen(onShowArticle = …)`, si bien que le retour rend l'article
  laissé sur place. L'identifiant n'entre **pas** dans la route (URL de flux local, identifiants
  d'entrée) : `nav.navigate("article")` puis
  `nav.currentBackStackEntry?.savedStateHandle?.set(ARTICLE_ID, id)`.
- **Bouton « remonter en haut » : pas d'`animateScrollToItem(0)` sur une longue liste.** Il avance
  par étapes — il réestime la hauteur des lignes et relance l'animation à chaque fois — si bien
  que la course durait des dizaines de secondes et finissait article par article.
  `LazyListState.animateToTop` / `LazyGridState.animateToTop` (`Components.kt`) font un défilement
  continu (`scrollBy(-hauteur d'écran)` + `withFrameNanos`), borné à `TOP_REWIND_MS` (600 ms),
  puis `scrollToItem(0)` pour se poser exactement en haut. Mesures : LazyList plafonne `scrollBy`
  à ~1,2 écran par image et l'émulateur ne rend que ~7 images/s pendant le défilement, d'où la
  borne de temps. Les trois listes s'en servent (articles, magazine, abonnements).
- **Bouton « descendre » : le bas de la page n'est pas le bas du flux.** La liste est paginée
  (200 articles) : `LazyListState.scrollToEnd` / `LazyGridState.scrollToEnd` se posent sur le
  dernier élément, laissent `EndReachedEffect` demander la page suivante, et recommencent tant
  que la liste grandit (`SCROLL_END_PAGE_WAIT_MS`, 3 s sans croissance = fin du périmètre —
  le délai couvre la requête Room d'une page). Le premier appui garde l'arrêt sur le dernier
  non-lu (`downTarget`) **sauf quand rien de lu ne suit** : là, l'arrêt serait la fin de la
  page chargée, et le bouton va jusqu'au dernier article du flux. Vers le haut, rien à faire :
  les pages s'ajoutent **en dessous** (`ORDER BY published DESC`), l'élément 0 est donc déjà
  le plus récent.
- Balayage de retour : `rememberBackSwipeState()` + `Modifier.backSwipe(state, onSwipe)` et le
  repère `SwipeBackHint` (`StreamScreen.kt`) sont partagés par `StreamScaffold` et
  `SubscriptionsScreen`. Le détecteur est posé sur la boîte qui **enveloppe** la liste : les lignes
  d'articles, qui ont leur propre geste horizontal, le consomment et gardent leur swipe — pour
  tester le retour, balayer sur un **interligne de date** (« Hier », à ~y=347) ou sur l'espace
  libre, jamais sur une ligne d'article. Seuil 120 px, sinon un frôlement change de page.
- Réglage « Page de démarrage » (`Prefs.HOME_SCREEN`) : `PressVM.homeScreen` démarre à **null**
  (DataStore répond de façon asynchrone) et le contenu de `MainActivity` n'est composé qu'une fois
  la valeur lue (`if (home == null) { Box; return@PressIATheme }`). Sans cette attente, la
  navigation démarrait sur le défaut « Accueil » et le `NavController` **gardait** cette entrée
  quand le réglage arrivait. La route de départ du `NavHost` est ensuite figée (`startRoute`, lue
  une fois) : recréer le graphe / `key(home)` éjectait l'utilisateur de l'écran Réglages au moment
  du choix. La valeur courante, elle, est suivie en direct (`homeRoute`) et pilote `hierarchical`,
  `atHome` et `goHome()`.
- Readability « persistant » coupé : le mode complet épinglé retombe dès qu'on **quitte la vue
  article** (`LaunchedEffect(current)` dans `MainActivity` → `vm.onLeftArticle()`), pas seulement
  au retour à l'accueil ; le balayage d'un article à l'autre ne change pas de destination et le
  conserve. Un article déjà extrait, lui, reste en complet à la réouverture : c'est `fullFetched`
  en base, pas l'épingle de session.
- **Mode automatique et lumière ambiante** (`ui/theme/Theme.kt`, `ambientDark`) : avec un capteur
  de luminosité, le thème bascule quand la lumière franchit le **seuil réglable** (Réglages →
  Affichage, `LIGHT_THRESHOLD`, 1 lux par défaut, curseur 0–200 lux, avec la **luminosité
  actuelle** affichée en dessous pour choisir en connaissance de cause) — à cette valeur ou moins :
  sombre. Le changement attend un **délai de bascule réglable** (Réglages → Affichage,
  `LIGHT_DELAY`, **3 s par défaut**, curseur 1–20 s) de conditions stables dans les deux sens : un
  nuage ou une lampe passagère ne fait pas clignoter. Le seuil et le délai sont relus à chaque
  lecture (`rememberUpdatedState`) — les modifier dans les Réglages s'applique donc sans
  redémarrage, bien que l'écouteur du capteur ne soit enregistré qu'une fois. Sans capteur,
  `isSystemInDarkTheme()` comme avant. Le thème part **sombre** tant que le capteur n'a rien dit,
  et la **première lecture** (démarrage) s'applique immédiatement : l'écran s'ouvre dans le thème
  de la pièce, l'attente ne concerne que les changements en cours d'usage. Vérifié sur l'émulateur
  (`adb emu sensor set light 0|1|2`, bascule mesurée à 3 s puis à 10 s après réglage).
- **Menu d'appui long** (accueil, flux, article) : il porte « Recherche » (portée du dossier, du
  flux ou du flux de l'article) et, en tête, le **compte** du flux ou « Local » — seulement si la
  précision distingue (`showFeedAccount` : plusieurs comptes, ou un compte + des flux locaux).
  L'écran article affiche ce compte **collé au nom du flux** (« Mediapart - Feedly »), même police
  et même couleur. Les écrans hôtes reçoivent `onSearch` et posent la portée via
  `PressVM.prepareSearch` avant de naviguer vers `search`. Le menu d'un article porte une icône par
  entrée (loupe pour Recherche, flèches haut/bas pour les marquages groupés).
- Toute suppression est **confirmée** : corbeille d'un flux local (`LoginScreen`, dialogue
  « Désabonner ce flux ? ») comme « Déconnecter » un compte. Les menus de flux des abonnements ne
  suppriment pas (déplacer / monter / descendre) — une nouvelle action destructrice doit passer par
  un dialogue.
- Ajout d'un flux local : sa **première synchronisation est annoncée** (`lockSync()` + `syncMsg`
  dans `PressVM.addLocalFeed`, appels appariés en `finally`), et `localFeedAdding` couvre
  l'enregistrement **et** cette sync (bouton désactivé, `done` appelé dans tous les cas) — sans
  quoi l'ajout restait muet pendant le premier téléchargement et un second appui relançait tout.
  `SubscriptionsScreen` (ajout dans un dossier) ferme sa boîte de dialogue et signale par un toast.
  Une URL **déjà abonnée** ne fait rien (`LocalFeedAdd.AlreadyExists` : ni déplacement de dossier,
  ni resynchronisation) et le dit — toast dans Comptes, message sous le champ. La comparaison
  passe par `feedUrlKey` (`LocalRssFetcher`) : schéma, `www.` de tête, barres finales et casse
  ne distinguent pas deux flux (le port et les paramètres restent comparés, en minuscules).
  Le rangement passe par `moveFeedsToFolder`, jamais par `dao.moveFeeds` direct : un dossier
  **fusionné** s'appelle `merged:…` et n'existe pas dans `categories`, l'écrire tel quel violait
  la clé étrangère et fermait l'application. `moveFeedsToFolder` ne déplace plus vers un
  identifiant inconnu (dossier supprimé ailleurs) : il ne fait rien.
- **Déplacer un flux part chez le compte** (`FeedProvider.moveSubscription`, appelé par
  `moveFeedsToFolder`) : la synchronisation réécrit `subscriptions.categoryId` avec ce que dit le
  service, donc un déplacement local seul revenait en arrière à la passe suivante. Feedly réécrit
  l'abonnement (`POST /v3/subscriptions` : `categories` **remplace** l'ensemble, vide = sans
  dossier ; le titre part avec, sinon Feedly le réinitialise au titre du flux) ; Inoreader et
  FreshRSS passent par `subscription/edit`, où `ac` est l'**action** (`edit`), `a` ajoute le
  dossier et `r` le retire, avec les identifiants du compte (honorés tels quels, l'alias
  `user/-/label/…` marche aussi). Inverser `ac` et `a` répond « OK » en créant un dossier nommé
  « edit » (constaté en direct) : ce sont les paramètres de l'API Google Reader. Un dossier d'un
  autre compte, ou local, n'a pas d'équivalent chez le service : le flux reçoit alors un dossier
  local homonyme, comme avant. Si le service refuse, le déplacement local n'est pas écrit et le
  bandeau le dit (« Déplacement refusé par le compte »), au lieu de repartir en silence à la
  synchronisation suivante.
- **Un dossier qui n'existe que localement (ou chez un autre compte) est créé chez le compte du
  flux** (`accountFolder` → `newFolderId`, `data/remote/FeedProvider.kt`, testé). Aucun agrégateur
  n'a d'appel « créer un dossier » : il naît en y rangeant un flux, et le déplacement emprunte
  donc le même chemin que `moveSubscription` — Feedly `POST /v3/subscriptions` avec un
  `category` inconnu, Inoreader/FreshRSS `subscription/edit` `a=user/<uid>/label/<nom>`.
  Deux particularités vérifiées en direct : **Feedly garde l'identifiant fourni** (d'où l'UUID
  tiré par `newFolderId`, réutilisé tel quel en base — le dossier revient identique à la
  synchronisation suivante) et **n'accepte aucun appel de création** (`POST /v3/categories/<id>`
  répond `400 unknown category`, `POST /v3/categories` `400 missing id key`) : c'est le
  déplacement qui le crée (voir la puce suivante pour un dossier vide). Un dossier du compte
  portant déjà ce nom est réutilisé, sinon
  chaque déplacement créerait un homonyme de plus. `DELETE /v3/categories/<id encodé>` supprime
  un dossier chez Feedly (les barres doivent être en `%2F`), mais l'application n'a pas d'action
  pour le faire : la gestion des dossiers du compte reste celle du service.
- **Créer un dossier vide** (icône « nouveau dossier » de l'Accueil) demande **dans quel compte**
  dès qu'il y a un choix (`folderOwners`, pur, testé : le seul compte connecté quand il n'y a pas
  de flux local, sinon « Local » puis chaque compte, le premier coché). Le dossier est alors créé
  chez le compte : `materializeFolder` y range **un flux du compte puis le remet à sa place** —
  c'est le seul moyen, aucun agrégateur n'ayant d'appel « créer un dossier » (Feedly `POST
  /v3/categories` répond `400 missing id key`, avec ou sans identifiant dans le corps). Feedly
  garde le dossier vide ; **Inoreader supprime une étiquette dès qu'elle est vide** (vérifié en
  direct), FreshRSS n'a pas été vérifié : pour eux le dossier est créé **localement** et rejoint
  le compte au premier flux qu'on y range (`moveFeedsToFolder`), ce que le message final dit.
- **Renommer un dossier** part chez le compte (`FeedProvider.renameCategory`) : Feedly
  `POST /v3/categories/<id>` avec le nouveau libellé (200, **l'identifiant est conservé** —
  vérifié en direct), Inoreader/FreshRSS par un `subscription/edit` **par flux** (`a` nouvelle
  étiquette, `r` ancienne), faute de renommage d'étiquette dans l'API Google Reader. L'identifiant
  d'un dossier Google Reader porte le nom (`user/<uid>/label/<nom>`) : `renamedFolderId` (pur,
  testé) le recalcule et le dossier local est recréé avec ses flux. Un refus du compte laisse le
  nom en place et le dit (« Renommage refusé par le compte »). Un dossier fusionné n'est renommé
  que dans ses parties locales : les comptes gardent leur nom.
- **La modale « Déplacer vers un dossier » affiche le compte sous chaque dossier** (petit, grisé)
  dès que la précision distingue (`showFeedAccount`) : un dossier fusionné en montre plusieurs
  (« Inoreader · Feedly »). Les lignes sont compactes (`MoveTargetRow`, pas des `ListItem` : à
  deux lignes, ils laissaient un grand vide entre les dossiers).
- **Synchro des flux locaux** : `LocalProvider.onFeed` est appelé à chaque flux reçu (8 en
  parallèle, compteur des flux terminés) et `syncDown` en fait « Réception des articles… (flux
  locaux 2/3 : MiniMachines.net) ». Les miniatures de `syncDown(withThumbs = true)` (ajout d'un
  flux) publient leur compteur dans `syncStep` comme le pipeline — sans cela la première synchro
  restait muette pendant le téléchargement des images.
- Listes : les boutons haut/bas flottent en bas au centre, les listes qui en ont laissent 72 dp de
  `contentPadding` sous le dernier élément (articles, Accueil) pour qu'il reste lisible.
- Périmètre orphelin (`MainActivity`) : quand le flux ou le dossier courant vient d'être supprimé
  depuis un autre écran (compte déconnecté, flux désabonné), un `LaunchedEffect` remet le périmètre
  sur « Tous les articles » et `lastContent` sur l'accueil — sinon le retour arrière remontait sur
  une liste vide, y compris après un détour par une autre page secondaire. La validation d'un
  dossier doit interroger `vm.folders` **et** `vm.categories` : un dossier fusionné porte un
  identifiant `merged:…` absent des catégories brutes (faux positif sinon).
- Recherche : **base locale uniquement** (`PressRepository.search`). La recherche du compte est
  partielle (Feedly ne renvoie que quelques entrées) : l'interroger masquait les articles déjà en
  base et le nombre de résultats affiché n'était pas celui de la bibliothèque. La requête du DAO
  est **paginée** (`LIMIT/OFFSET`, `SEARCH_PAGE` = 200) : l'écran charge la page suivante au
  défilement (`onEndReached` d'`ArticleList`) et écrit « 200+ résultat(s), faites défiler » tant
  que la fin n'est pas atteinte. L'œil de l'écran ne montre que les non-lus (`searchUnreadOnly`,
  filtre dans la requête) et le dit par un toast ; l'annulation d'un marquage groupé y reste
  affichée même avec l'œil fermé (les articles marqués lus sortent de la liste, c'est elle qui les
  ramène). Choisir une portée referme le panneau et relance la recherche en cours. La croix rouge
  du champ vide la requête et les résultats puis rend le clavier. Les résultats suivent la **vue des articles** des Réglages
  (`vm.viewMode`) : c'est une liste d'articles comme les autres.
- Écran Recherche : son état (requête, portée, option « texte », résultats) vit dans `PressVM`, pas
  dans le composable — ouvrir un article ou passer par une autre page puis revenir doit retrouver
  les résultats, et ils ne disparaissent qu'à la fermeture de l'application. Seuls un changement de
  portée ou de l'option les effacent. La recherche porte sur le **titre** par défaut (`withBody = 0`
  dans `dao.search`, l'auteur comptant comme un titre), l'interrupteur « Inclure le texte » ajoute
  résumé et contenu. Nouvelle recherche ⇒ `searchEpoch` change et la `key(...)` autour d'`ArticleList`
  reconstruit la liste : on repart en haut (une relecture après marquage groupé, elle, ne bouge pas
  la position). Page ouverte sans requête ⇒ `FocusRequester` + clavier. « Marquer tous lus »
  s'annule par `offerUndo` / `undoMarkRead`, qui relit aussi la recherche (les lignes portent leur
  propre état lu/non-lu). L'action est une **icône** de la barre des résultats, comme celle des
  listes : `DoneAll` devient `Undo` tant que l'annulation porte sur ces résultats
  (`undoOffer.ids` tous affichés) — un marquage fait ailleurs ne se confond pas avec celui-ci.
- Choix de portée (Recherche, Favoris) : `scopeEntries` / `ScopeChooser` (`ui/ScopeChooser.kt`)
  donnent **le même ordre que la page Accueil** — dossiers dans l'ordre de `vm.folders`, chacun
  suivi de ses flux triés par `sortOrder` (un dossier fusionné rassemble les flux de tous ses
  identifiants), puis les flux sans dossier — et les **mêmes icônes** : dossier, favicon du flux
  (`FeedAvatar`, décalé de 26 dp sous son dossier). Ne pas réénumérer `vm.categories` ou `vm.subs`
  à la main dans un nouvel écran : l'ordre et les repères divergeraient. Le défilement et la
  hauteur maximale restent à l'appelant (`heightIn(max = …)` **avant** `verticalScroll`, sinon la
  limite porte sur le contenu et le panneau déborde sans défiler).
- Écran Doublons : la requête couvre **tous** les non lus (`dao.unreadDuplicates`, **sans**
  `LIMIT`) et le regroupement se fait en Kotlin (`groupDuplicates`, testé). Un `LIMIT 200` trié par
  date rendait invisibles les doublons anciens — l'écran annonçait alors « aucun doublon » alors
  qu'il y en avait. L'écran est une `LazyColumn` : sans plafond, tout composer d'un coup peinerait
  sur une grosse base.
- Écran À propos : la recherche retient un sujet dès que son titre **ou** son résumé contient le
  terme, et le terme y est **surligné** (`termRanges`, casse ignorée, testé) — sans cela on ne
  voyait pas pourquoi un sujet était là, le mot n'apparaissant parfois qu'au bout du paragraphe.
  Le surlignage prend `primaryContainer`, qui suit le thème et le fond choisi.

## Commit automatique

`.opencode/plugins/autocommit.js` (API des plugins OpenCode 2.x) sait commit + push tout seul
(`git add -A`, message `Auto-commit <date>`) à chaque `session.idle`, mais il est **désactivé** :
`ENABLED = false` en tête du fichier, et `setup()` rend la main sans rien écouter. Repasser la
constante à `true` (puis redémarrer OpenCode) pour le réactiver. Un commit manuel avec un vrai
message reste préférable quand l'utilisateur le demande.
