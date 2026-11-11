package com.pressia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Un changement livré : sa date et son heure, son titre et, quand le commit en portait un, son
 * détail. L'heure ([time], `HH:mm`) est celle du commit : elle s'affiche sous la date et
 * départage deux changements du même jour.
 */
data class ChangelogItem(
    val date: String,
    val title: String,
    val detail: String? = null,
    val time: String? = null
)

/** Une version publiée et ses changements, du plus récent au plus ancien. */
data class ChangelogEntry(val version: String, val items: List<ChangelogItem>)

/**
 * Journal des modifications, du plus récent au plus ancien.
 *
 * **À tenir à jour** (voir AGENTS.md) : à chaque commit qui touche l'application, ajouter un
 * [ChangelogItem] en tête de l'entrée de la version courante, avec le titre et le corps du
 * message de commit. Le numéro de version, lui, avance tout seul au build suivant
 * (`version.properties`) ; c'est donc la version affichée par [ChangelogScreen] qu'il faut
 * reprendre dans l'entrée. L'historique d'avant le journal (208 commits) est dans
 * [changelogHistory], généré une fois depuis `git log` et à ne plus modifier — il n'est lu qu'à
 * l'ouverture de la page ([changelog] n'est pas une constante chargée au démarrage).
 */
fun changelog(): List<ChangelogEntry> = listOf(
    ChangelogEntry(
        "1.4",
        listOf(
            ChangelogItem(
                "2026-11-11",
                "Images : menu d'appui long (copier l'image), zoom au double appui dans la visionneuse",
                "- Dans un article comme dans l'image ouverte en plein écran, un appui long sur une " +
                    "image ouvre un menu : « Copier l'image », « Partager » et « Rechercher sur " +
                    "Internet » (Google Lens, qui cherche à partir de l'image). « Copier l'image » " +
                    "pose l'image elle-même sur le presse-papiers, et « Partager » envoie le fichier " +
                    "de l'image : elle se colle ou s'envoie comme une photo, sans passer par son " +
                    "adresse. L'appui simple continue d'ouvrir l'image en plein écran, et le double " +
                    "appui garde son action.\n" +
                    "- Visionneuse : le double appui zoome (2,5×), un second double appui dézoome, et " +
                    "un double appui maintenu puis glissé règle le zoom au doigt tant qu'il n'est " +
                    "pas relevé. Le déplacement d'une image zoomée suit désormais le doigt : il " +
                    "fallait tirer cinq fois plus loin à 5× (mesuré : 300 px de doigt = 300 px " +
                    "d'image).",
                time = "01:11"
            )
        )
    ),
    ChangelogEntry(
        "1.3",
        listOf(
            ChangelogItem(
                "2026-11-11",
                "Inoreader : tout marquer lu réversible, étoile = « Read later », Favoris par compte",
                "- « Tout marquer lu » n'emploie plus le « tout comme lu » de l'API Inoreader : ce dernier " +
                    "avance la borne du flux et le service refuse ensuite de rouvrir un article plus " +
                    "ancien, si bien que l'annulation ne tenait que jusqu'à la synchronisation suivante. " +
                    "Les articles non lus du périmètre sont marqués lus sur le serveur par lots : " +
                    "« Annuler » (pastille en bas ou flèche de la barre) les rouvre aussi côté Inoreader, " +
                    "et les étiquettes coûtent un appel par page de file au lieu d'un par article.\n" +
                    "- Un article Inoreader mis en favori rejoint le tableau « Read later » du compte " +
                    "(l'étiquette « starred », que le site Inoreader nomme « Read later ») ; le retirer " +
                    "l'en retire, et le tableau redescend à la synchronisation. Une étoile posée sur le " +
                    "site apparaît donc dans les Favoris, et inversement. Les favoris des flux locaux " +
                    "restent locaux.\n" +
                    "- Le filtre des Favoris distingue « À lire plus tard - Feedly » et « À lire plus tard - " +
                    "Inoreader » : chaque tableau ne montre que les articles de son compte, y compris " +
                    "ceux d'un flux désabonné. L'entrée n'apparaît que si le compte est connecté, et le " +
                    "déconnecter ramène le filtre à « Tous les favoris », qui reste la réunion des " +
                    "étoiles PressIA et des tableaux.\n" +
                    "- La provenance du tableau est rangée avec l'article (mise à jour de la base) : la " +
                    "synchronisation suivante réimporte une fois chaque tableau pour la poser.",
                time = "00:14"
            )
        )
    ),
    ChangelogEntry(
        "1.2",
        listOf(
            ChangelogItem(
                "2026-11-10",
                "Téléchargement des images : le compteur avance même quand une image échoue",
                "- Synchronisation, étape « Téléchargement des images… n/total » : le compteur " +
                    "n'avançait que sur les images réussies. Il se figeait donc au nombre de " +
                    "réussites (« 42/78 ») pendant que les images restantes épuisaient leur " +
                    "délai (30 s chacune, six à la fois) — la synchronisation semblait bloquée " +
                    "alors qu'elle avançait. Chaque image terminée compte désormais, échec " +
                    "compris, et le compteur atteint toujours le total ; les images non " +
                    "récupérées restent annoncées en fin de synchronisation, avec leur détail. " +
                    "Délai resserré : 5 s quand l'image ne répond pas, 20 s au total quand " +
                    "elle répond mais descend lentement. Le total, lui, est normal : il compte " +
                    "les images (une à deux par article), pas les articles.",
                time = "14:14"
            )
        )
    ),
    ChangelogEntry(
        "1.1",
        listOf(
            ChangelogItem(
                "2026-11-10",
                "Délai de bascule réglable, progression des marquages groupés, version 1.x",
                "- Mode automatique : le délai de bascule devient un réglage (1 à 20 s, 3 s par " +
                    "défaut, au lieu de 5 s fixes). Comme le seuil, il s'applique dès qu'on le " +
                    "modifie — l'écouteur du capteur n'était enregistré qu'une fois et gardait " +
                    "l'ancienne valeur jusqu'au redémarrage.\n" +
                    "- Marquage groupé qui part article par article (annulation d'un « tout marquer " +
                    "lu », compte sans marquage de masse, favoris exclus) : une barre en bas montre " +
                    "l'envoi au serveur dès 10 articles, une étape par compte (Feedly, Inoreader). " +
                    "Elle ne repart pas de zéro si l'on annule pendant l'envoi, et les marquages pas " +
                    "encore partis sont annulés — le serveur ne les voit jamais.\n" +
                    "- Version : le numéro avance désormais par version mineure (1.1, 1.2…) au lieu " +
                    "du correctif 1.0.x.",
                time = "02:12"
            )
        )
    ),
    ChangelogEntry(
        "1.0.53",
        listOf(
            ChangelogItem(
                "2026-11-10",
                "Menu de sélection au bon endroit, recherche complète, échecs de synchro détaillés",
                "- Menu de sélection : écart constant de 40 dp avec le doigt, au-dessus quand il y a " +
                    "la place, sinon dessous — il ne couvre plus la ligne touchée. Compose 1.7 " +
                    "fournit un rectangle de sélection décalé de ~65 dp vers le haut (mesuré à " +
                    "l'écran), d'où un menu posé plusieurs lignes trop haut.\n" +
                    "- Le même menu (copier, tout sélectionner, rechercher, traduire) sert aussi à " +
                    "l'À propos et au journal des modifications, dont le texte devient sélectionnable.\n" +
                    "- Recherche : les résultats arrivent par pages de 200 au défilement (le compte " +
                    "affiche « 200+ » tant qu'il en reste) ; l'état (requête, portée, option) survit " +
                    "à l'ouverture d'un article ; choisir une portée referme le panneau et relance " +
                    "la recherche en cours ; l'œil annonce « Seulement les non-lus » ou « Tous les " +
                    "résultats » ; après un marquage groupé, « annuler » reste visible même quand " +
                    "les lus sont masqués et rétablit la liste.\n" +
                    "- Fin de synchronisation : un échec par compte (session expirée, quota, réseau) " +
                    "est conservé et nommé, et le bouton « Voir le détail » ouvre une modale qui " +
                    "liste les comptes en échec et les images non récupérées.\n" +
                    "- En-tête d'article : le compte (Feedly, Inoreader, Local) est affiché à côté " +
                    "du nom du flux, en gris foncé — le nom du flux reste en couleur primaire.\n" +
                    "- Miniature d'article : la première image jointe est retenue (la dernière " +
                    "l'emportait, à rebours du flux).\n" +
                    "- Balayage d'article : la liste des voisins est bornée à 400 identifiants, " +
                    "comme les articles collants de la session.\n" +
                    "- WorkManager est retiré : plus rien ne planifie de synchronisation (elle ne " +
                    "tourne qu'application ouverte).",
                time = "00:28"
            )
        )
    ),
    ChangelogEntry(
        "1.0.52",
        listOf(
            ChangelogItem(
                "2026-11-09",
                "Feedly sans plafond, images par priorité, extraction neutre",
                "- Feedly « tout marquer lu » : l'énumération des non-lus va jusqu'à épuisement " +
                    "(plus de plafond à 5000) — un flux ou le compte entier au-delà laissait les " +
                    "plus anciens non lus côté serveur, rouverts à la synchronisation suivante.\n" +
                    "- Images : la file suit la priorité — article le plus récent d'abord, sa " +
                    "miniature puis les images de son corps — au lieu de toutes les miniatures " +
                    "puis tous les corps.\n" +
                    "- Extraction Readability : plus d'en-tête Referer « google.com » (il n'avait " +
                    "aucun motif) ; le titre YouTube (oEmbed) passe par le client partagé, avec " +
                    "un délai total au lieu d'aucun.\n" +
                    "- Feedly : l'identifiant de compte est mis en cache par jeton, plus dans un " +
                    "champ unique qui n'était jamais rempli et aurait mélangé deux comptes.",
                time = "00:05"
            )
        )
    ),
    ChangelogEntry(
        "1.0.51",
        listOf(
            ChangelogItem(
                "2026-11-08",
                "Images : détail des échecs, SVG, plafond de taille",
                "- Fin de synchronisation : « Images non récupérées : 3 images » avec un bouton " +
                    "« Voir le détail » — flux, article, cause et adresse de chaque image, liste " +
                    "défilante avec son indicateur ; toucher une adresse la copie (et la modale le " +
                    "confirme). Le bandeau reste 5 s quand il porte ce bouton.\n" +
                    "- Cause de chaque échec : code HTTP, délai dépassé, hôte introuvable, connexion " +
                    "coupée, image trop lourde, image vide ou illisible.\n" +
                    "- Les SVG sont pris en charge : miniatures, images du corps et favicons.\n" +
                    "- Plafond de 10 Mo sur toute image (le corps compris) et délai total de 30 s : " +
                    "les originales de 30 Mo servies par certains CDN sont coupées et annoncées " +
                    "« image trop lourde » au lieu de finir en « délai dépassé ».",
                time = "23:36"
            )
        )
    ),
    ChangelogEntry(
        "1.0.50",
        listOf(
            ChangelogItem(
                "2026-11-08",
                "Inoreader : « tout marquer lu », jeton de renouvellement, À propos",
                "- « Tout marquer comme lu » passe désormais par le bouton du site : marquage de masse côté " +
                    "Inoreader, un appel par flux, tout le compte pour la vue « Tous les articles » — le " +
                    "serveur est à jour tout de suite.\n" +
                    "- Renouvellement du jeton OAuth : le jeton de renouvellement renvoyé par Inoreader est " +
                    "toujours repris ; sans nouveau jeton, l'ancien reste valable (RFC 6749).\n" +
                    "- À propos : Inoreader apparaît dans la présentation et dans les fonctionnalités " +
                    "(marquage groupé, état lu/non lu) avec sa limite — une annulation de marquage groupé " +
                    "reste locale, le site ne rouvre pas un article plus ancien.",
                time = "11:15"
            )
        )
    ),
    ChangelogEntry(
        "1.0.49",
        listOf(
            ChangelogItem(
                "2026-11-08",
                "Nommage des APK : PressIA-<version>-<type>-<date>.apk",
                "Les APK de debug et release portent désormais un nom explicite " +
                    "incluant le numéro de version, le type (debug/release) et la date du build " +
                    "(ex. : PressIA-1.0.49-release-20261108.apk). Implémenté via la tâche " +
                    "renameApks branchée sur assemble* dans build.gradle.kts.",
                time = "10:30"
            )
        )
    ),
    ChangelogEntry(
        "1.0.48",
        listOf(
            ChangelogItem(
                "2026-11-08",
                "Thème auto sur la lumière, Réglages réorganisés, action Recherche, compte dans les menus",
                "- Mode automatique : avec un capteur de luminosité, le thème suit la lumière ambiante " +
                    "par rapport à un seuil réglable (Réglages → Affichage, 1 lux par défaut, curseur " +
                    "0–200 lux avec la luminosité actuelle affichée en direct) : à cette valeur ou " +
                    "moins, sombre ; au-dessus, clair. Il part sombre tant que le capteur n'a pas " +
                    "parlé, la première lecture s'applique immédiatement au démarrage, et les " +
                    "changements suivants attendent 5 secondes de conditions stables dans les deux " +
                    "sens — passage au sombre comme retour au clair. Sans capteur, le thème du " +
                    "système s'applique comme avant.\n" +
                    "- Réglages : deux blocs nets, « Listes d'articles » et « Dans un article » — " +
                    "les icônes d'action de l'écran article rejoignent le second.\n" +
                    "- Action « Recherche » partout où une action se choisit (bouton de raccourci, " +
                    "glissements, double-tap, balayages) ; elle ouvre la recherche sur le flux de " +
                    "l'article, et l'icône Recherche est ajoutée en tête des icônes d'action par " +
                    "défaut.\n" +
                    "- Menu d'appui long d'un dossier, d'un flux ou d'un article : entrée " +
                    "« Recherche » avec la portée de la ligne ; les entrées du menu d'article " +
                    "portent leur icône (loupe, flèches haut/bas).\n" +
                    "- Le compte du flux (ou « Local ») apparaît en tête de ces menus et, dans " +
                    "l'article, collé au nom du flux avec « - », même police et même couleur " +
                    "(« Mediapart - Feedly ») — seulement quand plusieurs comptes ou des flux " +
                    "locaux cohabitent ; sinon la mention est masquée.",
                time = "01:37"
            )
        )
    ),
    ChangelogEntry(
        "1.0.47",
        listOf(
            ChangelogItem(
                "2026-11-08",
                "Images : délai réseau ramené à 10 secondes",
                "Les téléchargements d'images attendaient jusqu'à 30 s (durée totale) avant " +
                    "d'abandonner : une seule image morte retenait la passe et la synchro. " +
                    "Miniatures (`ThumbStore`) comme images du corps et favicons (client OkHttp de " +
                    "Coil) sont désormais limitées à 10 s en connexion, en lecture et au total ; " +
                    "une image qui ne répond pas est comptée en échec et son flux est nommé dans le " +
                    "message de fin.",
                time = "00:24"
            )
        )
    ),
    ChangelogEntry(
        "1.0.46",
        listOf(
            ChangelogItem(
                "2026-11-08",
                "Images en timeout : les flux concernés sont nommés, la synchro reste réussie",
                "Une image qui n'arrive pas (timeout, 404…) ne fait plus passer la synchronisation " +
                    "pour un échec : les articles et le reste étant passés, l'heure de dernière " +
                    "synchronisation est mise à jour normalement, et le message de fin nomme les " +
                    "flux dont une image manque (« Images non récupérées : FrAndroid, … », quatre " +
                    "noms puis « et N autres flux »). La passe images elle-même est non bloquante : " +
                    "une erreur inattendue est signalée sans interrompre la synchro.",
                time = "00:15"
            )
        )
    ),
    ChangelogEntry(
        "1.0.45",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Vue liste : le bouton de raccourci reste à droite sur un article sans image",
                "Sur un article sans image, la vue liste déplaçait le rond lu/non-lu dans la place " +
                    "de l'image et retirait le bouton de raccourci du bord droit. La place vide " +
                    "montre désormais le favicon du flux (comme les vues carte et compacte) et le " +
                    "bouton de raccourci garde sa position à droite, quel que soit le réglage " +
                    "(lu/non-lu, favoris, partage, Instapaper).",
                time = "23:31"
            )
        )
    ),
    ChangelogEntry(
        "1.0.44",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Réglages encadrés et note replacée ; la recherche suit la vue des articles",
                "- Chaque réglage est désormais dans un fin liseré, avec son libellé, son option et " +
                    "son explication : la page se lit d'un coup d'œil, sans confondre un " +
                    "interrupteur avec le paragraphe voisin.\n" +
                    "- La note « Un seul réglage de volume… » ouvrait le milieu des interrupteurs de " +
                    "synchronisation ; elle est remontée sous le titre de sa section, où elle " +
                    "annonce le volume et la fréquence qu'on règle plus bas.\n" +
                    "- Les résultats de la Recherche suivent la vue choisie dans Réglages (compacte, " +
                    "liste, carte, magazine) au lieu d'être toujours en liste.",
                time = "23:15"
            )
        )
    ),
    ChangelogEntry(
        "1.0.43",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Liste d'articles : plus de fermeture sur un article au corps énorme (CursorWindow)",
                "Android lit les résultats SQLite par fenêtre d'environ 2 Mo : un seul article dont " +
                    "le HTML dépassait cette taille rendait la requête entière illisible — " +
                    "« Couldn't read row 54, col 0 from CursorWindow » — et l'application se " +
                    "fermait en affichant la liste. Le HTML est désormais plafonné à 300 000 " +
                    "caractères à l'écriture (corps du flux comme extraction Readability), et une " +
                    "migration coupe une fois les corps déjà enregistrés, pour les téléphones déjà " +
                    "touchés.",
                time = "22:56"
            )
        )
    ),
    ChangelogEntry(
        "1.0.42",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Un flux local déjà abonné est reconnu quelle que soit l'écriture de l'URL",
                "La comparaison se faisait sur l'URL exacte : la même adresse saisie avec " +
                    "« http:// » au lieu de « https:// », avec ou sans « www. », avec une barre " +
                    "finale en plus ou une casse différente, était acceptée comme un nouveau flux — " +
                    "deux lignes pour un seul flux, deux téléchargements, et les articles en double " +
                    "dans les listes. La clé de comparaison (feedUrlKey) ignore le schéma, le " +
                    "« www. » de tête, les barres finales et la casse : " +
                    "« HTTP://MiniMachines.NET/Feed/ » et « https://www.minimachines.net/feed » " +
                    "sont le même flux. Le port, le chemin et les paramètres restent dans la clé " +
                    "(en minuscules) : deux flux distincts d'un même hôte restent séparés.",
                time = "22:44"
            )
        )
    ),
    ChangelogEntry(
        "1.0.41",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Ajout d'un flux local dans un dossier fusionné : plus de plantage",
                "L'ajout écrivait le dossier choisi tel quel dans subscriptions.categoryId, alors " +
                    "qu'un dossier fusionné n'a pas d'identifiant en base (« merged:informatique ») : " +
                    "la clé étrangère refusait l'écriture et l'application se fermait " +
                    "(SQLiteConstraintException). L'ajout passe désormais par le déplacement commun " +
                    "(moveFeedsToFolder), qui retrouve le dossier réel du flux et le crée en local " +
                    "s'il n'existe que chez un compte ; un identifiant devenu inconnu ne fait " +
                    "simplement plus rien au lieu de faire planter la base.",
                time = "22:27"
            )
        )
    ),
    ChangelogEntry(
        "1.0.40",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Accueil : espace sous le dernier flux ; flux local déjà abonné ; synchro locale nommée",
                "- Accueil : 72 dp de marge en bas de la liste, le dernier flux ne passe plus sous " +
                    "les boutons haut/bas.\n" +
                    "- Ajout d'un flux local : une URL déjà abonnée ne fait plus rien (ni " +
                    "déplacement de dossier, ni resynchronisation) et le dit (toast dans Comptes, " +
                    "message sous le champ).\n" +
                    "- Synchro des flux locaux : le bandeau nomme le flux reçu au fil de l'eau " +
                    "(« Réception des articles… (flux locaux 2/3 : MiniMachines.net) ») et affiche " +
                    "le nombre d'images téléchargées, comme pour les agrégateurs.\n" +
                    "- AGENTS.md : chemin du JDK 17 complet (la copie 17.0.20.1 n'a plus javac).",
                time = "21:34"
            )
        )
    ),
    ChangelogEntry(
        "1.0.39",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Marquer lu : la file montante se vide enfin (réponses texte d'Inoreader)",
                "Les deux appels d'Inoreader qui répondent en texte brut — edit-tag (« OK ») et le " +
                    "jeton CSRF — étaient déclarés en String, donc confiés au convertisseur JSON : " +
                    "chaque poussée d'un lu échouait sur JsonDecodingException alors que le serveur " +
                    "avait bien appliqué le marquage. La ligne restait dans la file, réessayée à " +
                    "chaque synchro sans jamais aboutir, et le lu local était repris par la " +
                    "synchronisation suivante. edit-tag ne rend plus rien (Unit) et le jeton CSRF " +
                    "est lu en texte ; un échec de poussée est désormais journalisé au lieu de " +
                    "rester muet. Constaté en direct sur une session cookie Inoreader.",
                time = "16:15"
            )
        )
    ),
    ChangelogEntry(
        "1.0.38",
        listOf(
            ChangelogItem(
                "2026-11-07",
                "Dossiers et date de synchro perdus à chaque relance (copie automatique)",
                "La copie automatique était réimportée à chaque lancement dès qu'on n'avait ni flux " +
                    "local ni favori — cas d'un compte Feedly seul. Comme elle ne contient que les " +
                    "dossiers locaux, chaque flux du compte perdait son dossier (ils tombaient tous " +
                    "dans « Flux sans dossier ») et la date de dernière synchro repassait à " +
                    "« Jamais synchronisé » ; la synchro suivante les remettait en place. Elle n'est " +
                    "plus réimportée que si la base est vraiment vide (aucun abonnement, aucun " +
                    "favori), et le fichier ne contient plus que les flux locaux.",
                time = "00:21"
            )
        )
    ),
    ChangelogEntry(
        "1.0.37",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "À propos à jour, journal plus large",
                "L'écran À propos crédite DeepSeek 4.1 Flash et indique Android 8.0 (API 26) comme " +
                    "minimum. Le résumé « Fonctionnalités et règles » a été relu et complété : " +
                    "synchronisation à la fréquence réglée tant que l'application est ouverte, " +
                    "mise en forme et tableaux de l'article, vidéos YouTube et iframes non vidéo, " +
                    "balayage qui suit la liste d'où l'on vient (Favoris, Recherche, Doublons), " +
                    "règle Feedly des 31 jours, sauvegarde Google, statistiques, identifiants " +
                    "Inoreader et flux locaux en HTTP. Dans le journal des modifications, le " +
                    "nombre de changements par version disparaît et la colonne de date se " +
                    "resserre au profit du texte.",
                time = "23:46"
            )
        )
    ),
    ChangelogEntry(
        "1.0.36",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "Compose 1.7.8 et Material3 1.3.2 (plafond du SDK 34)",
                "Compose passe de 1.7.4 à 1.7.8 (BOM 2025.03.01) et Material3 de 1.3.1 à 1.3.2, " +
                    "les icônes Material suivent en 1.7.8 (dernière version publiée). C'est le " +
                    "maximum compatible avec compileSdk 34 : Compose 1.8 exige le SDK 35, " +
                    "Compose 1.12 le SDK 37 et AGP 9.1. Kotlin, AGP, Gradle et targetSdk sont " +
                    "inchangés, d'où aucun changement de comportement attendu.",
                time = "23:31"
            )
        )
    ),
    ChangelogEntry(
        "1.0.35",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "File montante vidée jusqu'au bout, balayage Recherche et Doublons, plafonds",
                "La file des modifications en attente n'a plus de plafond de pages : elle est " +
                    "poussée jusqu'à ce qu'elle soit vide (un gros marquage groupé ne reste plus " +
                    "aux trois quarts en attente). Le balayage d'un article ouvert depuis la " +
                    "Recherche ou les Doublons suit désormais la liste de l'écran, comme les " +
                    "Favoris. Les titres de vidéos YouTube sont gardés en cache borné (200) au " +
                    "lieu de s'accumuler. Un flux ou une page dont la taille annoncée dépasse le " +
                    "plafond est refusé avant même d'être téléchargé. Le journal des modifications " +
                    "n'est plus construit qu'à l'ouverture de sa page.",
                time = "22:58"
            )
        )
    ),
    ChangelogEntry(
        "1.0.34",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "Sauvegarde Google des données locales, balayage des favoris et nettoyages",
                "La sauvegarde Google garde les jetons de compte mais plus le contenu des " +
                    "articles : la base (HTML) et les miniatures sont exclues, sinon les 25 Mo " +
                    "de quota faisaient sauter la sauvegarde entière. Les flux RSS locaux, les " +
                    "favoris PressIA et les lus de ces flux sont recopiés dans un petit fichier " +
                    "(files/sauvegarde-auto.json), réécrit à chaque mise en arrière-plan et " +
                    "après chaque synchro, puis réimporté à la première ouverture après une " +
                    "restauration ; les garde-fous de synchro de l'ancienne base sont remis à " +
                    "zéro, sinon la première passe était jugée « récente » et sautée. Le " +
                    "balayage d'un article ouvert depuis les Favoris suit désormais la liste " +
                    "des favoris (il revenait en arrière au lieu de passer au favori suivant). " +
                    "Nettoyages : deux crochets JavaScript morts du lecteur YouTube, quatre " +
                    "méthodes DAO sans appelant, trois dépendances Paging inutilisées, " +
                    "identifiant impossible pour le dossier vide (NUL plutôt que chaîne vide), " +
                    "et readCapped déplacé dans data/Bytes.kt. La restauration d'un gros jeu de " +
                    "favoris est découpée par paquets de 500 (limite de variables SQLite des " +
                    "anciens Android).",
                time = "22:18"
            )
        )
    ),
    ChangelogEntry(
        "1.0.33",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "Texte barré des articles",
                "Le texte barré s'affiche comme tel : balises <s>, <del> et <strike> des flux, " +
                    "et style en ligne line-through. Les deux traits (souligné et barré) se " +
                    "combinent dans la même plage.",
                time = "19:37"
            )
        )
    ),
    ChangelogEntry(
        "1.0.32",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "Soulignement des articles",
                "Le texte souligné s'affiche comme tel : balises <u> et <ins> des flux, et style " +
                    "en ligne text-decoration: underline. La mise en forme ne coupe pas la " +
                    "phrase : c'est une plage du texte, comme le gras et l'italique.",
                time = "19:25"
            )
        )
    ),
    ChangelogEntry(
        "1.0.31",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "Iframe non vidéo, tableaux lisibles et surlignage",
                "Une iframe d'article n'est plus prise pour une vidéo : seuls YouTube, Vimeo, " +
                    "Dailymotion et Twitch donnent un lecteur, les autres (carte d'un autre " +
                    "article, formulaire, commentaires) s'affichent en lien. Un tableau a " +
                    "désormais une ligne d'en-tête sur fond plus marqué et des lignes au fond " +
                    "alterné. Le surlignage des articles (balise <mark>) est rendu avec la " +
                    "couleur du thème.",
                time = "10:44"
            )
        )
    ),
    ChangelogEntry(
        "1.0.30",
        listOf(
            ChangelogItem(
                "2026-11-06",
                "Balises HTML des articles, extraction Next et échec Readability",
                "Le HTML du flux est mieux rendu : titres (h1 à h6), gras, italique, listes à " +
                    "puces, citations et légendes d'image ont leur style. Un tableau était perdu " +
                    "entièrement, il s'affiche désormais en lignes et colonnes, en-tête compris. " +
                    "Readability extrait le corps des articles de Next sans y mêler les " +
                    "commentaires. Et quand l'extraction échoue, l'article reste affiché dans sa " +
                    "version du flux RSS avec un message qui explique pourquoi (protection du " +
                    "site ou page injoignable) - au lieu de remplacer le texte par un extrait " +
                    "tronqué, et en relâchant l'épingle Readability.",
                time = "08:04"
            )
        )
    ),
    ChangelogEntry(
        "1.0.29",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Plafond de taille pour les flux et les pages d'articles",
                "Les corps de flux et les pages d'articles partaient entiers en mémoire, sans " +
                    "plafond : huit flux interrogés en parallèle, un serveur qui répond une " +
                    "archive ou un corps sans fin, et la synchro mourait en OutOfMemory. Le " +
                    "même plafond qu'une miniature s'applique désormais - 5 Mo pour un flux, " +
                    "8 Mo pour une page d'article - et un dépassement est un échec de " +
                    "récupération, journalisé pour le flux local concerné.",
                time = "09:53"
            )
        )
    ),
    ChangelogEntry(
        "1.0.28",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Minuterie de synchronisation au premier plan",
                "Le retour au premier plan resynchronisait, mais une application laissée ouverte " +
                    "au même écran ne se resynchronisait jamais non plus : la minuterie manquait. " +
                    "Tant que l'écran est visible, une boucle dort jusqu'à l'échéance du réglage " +
                    "« Fréquence de synchronisation » puis repasse par la même porte (option, " +
                    "sources et fraîcheur revérifiées), et attend la fin de la passe. Elle s'arrête " +
                    "quand l'application passe en arrière-plan ; fréquence « manuelle » : elle ne " +
                    "synchronise pas mais reste à l'écoute d'un changement de réglage. Une " +
                    "tentative en échec n'est pas retentée toutes les minutes.",
                time = "08:42"
            )
        )
    ),
    ChangelogEntry(
        "1.0.27",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Synchronisation au retour dans l'application",
                "La synchro automatique ne partait qu'à la création de l'activité : une " +
                    "application laissée en arrière-plan puis rouverte (processus toujours vivant) " +
                    "ne resynchronisait jamais, et « Dernière synchro » pouvait dater de la veille " +
                    "malgré un réglage à dix minutes. Chaque retour au premier plan repasse " +
                    "désormais par la même porte — option et fenêtre de fraîcheur revérifiées — et " +
                    "n'annule pas une synchro déjà en cours.",
                time = "08:11"
            )
        )
    ),
    ChangelogEntry(
        "1.0.26",
        listOf(
            ChangelogItem(
                "2026-11-05",
                "Vidéo : un seul lecteur, celui du site ; le retour fonctionne",
                "Le réglage « Vidéos YouTube » disparaît, et avec lui le lecteur maison : la vidéo " +
                    "se lit désormais sur la page d'intégration de YouTube, avec ses propres " +
                    "commandes (lecture, barre d'avancée, qualité, sous-titres) — et elle ne se met " +
                    "plus en pause toute seule quelques secondes après le début, ce que faisait la " +
                    "page de lecture (son lecteur se croit invisible). " +
                    "Autre correctif : quand la vidéo jouait, l'appui sur Retour était consommé " +
                    "avant l'application — il ne quittait plus l'article, et il arrêtait même la " +
                    "lecture au passage. La touche est maintenant rendue à l'application, en plein " +
                    "écran comme dans l'article : le plein écran se referme d'abord, l'article se " +
                    "quitte ensuite.",
                time = "00:28"
            )
        )
    ),
    ChangelogEntry(
        "1.0.25",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Statistiques indexées, rafraîchissement allégé",
                "« Passés en lu sans ouvrir » cherchait, pour chaque article lu, un évènement de " +
                    "lecture : la sous-requête balayait toute la table d'évènements, une fois par " +
                    "article. Les colonnes articles.readAt, reading_events.articleId et " +
                    "reading_events.openedAt sont indexées (base 6 → 7). Et chaque " +
                    "rafraîchissement chargeait 2000 articles complets, HTML compris, pour n'en " +
                    "garder que les identifiants du balayage : une requête d'identifiants seuls " +
                    "les fournit.",
                time = "08:58"
            )
        )
    ),
    ChangelogEntry(
        "1.0.24",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Inoreader, doublons et comptes parallèles : requêtes et verrous",
                "« Tout marquer lu » sur Inoreader demandait le jeton CSRF article par article, " +
                    "soit deux requêtes HTTP par article : il est demandé une fois pour la " +
                    "boucle. « Marquer lus tous les doublons » vidait la file de synchronisation " +
                    "une fois par doublon ; un seul envoi groupé la vide une fois. Enfin, des " +
                    "comptes synchronisés en parallèle perdaient des préférences (sessions " +
                    "expirées, totaux de réconciliation) : ces tables JSON de DataStore se " +
                    "modifient désormais sous verrou.",
                time = "08:06"
            )
        )
    ),
    ChangelogEntry(
        "1.0.23",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Dossier vide : plus aucun article ; articles collants bornés",
                "Un dossier sans flux affichait tous les articles de l'application : la requête " +
                    "prenait « dossier vide » pour « pas de dossier ». Il n'affiche plus rien, et " +
                    "« tout marquer lu » n'y marque plus tout le compte (même confusion). D'autre " +
                    "part, les articles gardés visibles après lecture pendant la session " +
                    "s'accumulaient sans limite, chacun lié un par un dans la requête : la liste " +
                    "est bornée à 400, les plus récents gardés.",
                time = "01:43"
            )
        )
    ),
    ChangelogEntry(
        "1.0.22",
        listOf(
            ChangelogItem(
                "2026-11-04",
                "Sélection : chercher et traduire ; Feedly garde ses non-lus",
                "Le menu de sélection d'un article offrait « Copier » et « Tout sélectionner » ; " +
                    "il ajoute « Rechercher sur Internet » et « Traduire », qui ouvrent le " +
                    "navigateur sur le texte sélectionné. Compose ne livre pas la sélection à ce " +
                    "menu : les deux actions la font copier, puis relisent le presse-papiers (le " +
                    "texte est donc copié au passage). Côté réglages, une option « Feedly : " +
                    "garder non lu au-delà de 31 jours », activée par défaut, empêche les " +
                    "synchronisations de fermer les articles que Feedly marque lus de lui-même " +
                    "passé ce délai : leur état non lu est conservé.",
                time = "01:27"
            )
        )
    ),
    ChangelogEntry(
        "1.0.21",
        listOf(
            ChangelogItem(
                "2026-11-03",
                "Retour sur un article : c'est bien celui qu'on avait laissé",
                "L'article affiché était une valeur unique de l'application, réécrite à chaque " +
                    "ouverture : après un détour par Favoris (ou la Recherche), revenir sur " +
                    "l'article ouvert auparavant rendait celui qu'on venait d'ouvrir depuis " +
                    "Favoris. Chaque entrée de navigation porte désormais son propre article, y " +
                    "compris celui atteint par balayage horizontal : le retour rend exactement " +
                    "l'article laissé à cet endroit. Le corrigé précédent s'applique toujours " +
                    "(retour depuis Favoris vers la page d'où l'on venait).",
                time = "00:04"
            )
        )
    ),
    ChangelogEntry(
        "1.0.20",
        listOf(
            ChangelogItem(
                "2026-11-02",
                "Retour depuis Favoris : l'article ne se rouvre plus",
                "Depuis Favoris, ouvrir un article puis revenir à la liste laissait l'article " +
                    "comme « dernière page de contenu » : le retour suivant, au lieu de rendre " +
                    "la page d'où l'on venait, dépilait vers une destination disparue puis la " +
                    "recréait — l'article se rouvrait. La cible du retour est maintenant lue " +
                    "dans la pile de navigation (la première page de contenu réellement située " +
                    "sous l'écran utilitaire), la mémoire de la dernière page ne servant que de " +
                    "secours. Ouvrir Favoris depuis un article y ramène toujours, comme avant.",
                time = "23:48"
            )
        )
    ),
    ChangelogEntry(
        "1.0.19",
        listOf(
            ChangelogItem(
                "2026-11-02",
                "Liste : le bouton haut remonte d'un trait",
                "Sur une très longue liste, le bouton « remonter en haut » avançait par étapes : " +
                    "chaque saut réestimait la hauteur des lignes et relançait l'animation, si " +
                    "bien que la course durait des dizaines de secondes et finissait article par " +
                    "article. La remontée est maintenant un défilement continu, à la vitesse " +
                    "maximale que la liste peut composer, borné dans le temps : quelques images " +
                    "suffisent quel que soit le nombre d'articles, puis la liste se pose " +
                    "exactement tout en haut. Les trois listes (articles, vue magazine, " +
                    "abonnements) en profitent.",
                time = "23:14"
            )
        )
    ),
    ChangelogEntry(
        "1.0.18",
        listOf(
            ChangelogItem(
                "2026-11-02",
                "Favoris : un article lu depuis la liste ne s'y invite plus",
                "La liste « collante » garde visibles les articles passés en lus pendant la " +
                    "session, pour qu'ils ne disparaissent pas sous le doigt. Cet égard ignorait " +
                    "le périmètre : l'article ouvert depuis « Tous les articles » réapparaissait " +
                    "donc en tête de Favoris alors qu'il n'y est pas étoilé. Les articles " +
                    "retenus ne sont plus dispensés que du filtre « non-lus seuls » : le flux " +
                    "affiché et le jeu de favoris s'appliquent aussi à eux.",
                time = "22:53"
            )
        )
    ),
    ChangelogEntry(
        "1.0.17",
        listOf(
            ChangelogItem(
                "2026-11-01",
                "Liste : l'appui long ne fait plus sauter la vue",
                "Le menu d'appui long (« marquer lus les plus récents / plus anciens ») écrivait " +
                    "en base AVANT de rendre les articles « collants ». Sous le filtre « non-lus " +
                    "seuls » (par défaut), la liste réactive les retirait donc le temps d'une " +
                    "émission, puis les réaffichait : les lignes disparaissaient et revenaient, " +
                    "et le défilement sautait de position au lieu de rester sur l'article " +
                    "regardé. Les identifiants sont maintenant ajoutés aux articles « collants » " +
                    "avant l'écriture, comme pour une lecture simple : ils restent en place, " +
                    "simplement estompés.",
                time = "16:31"
            )
        )
    ),
    ChangelogEntry(
        "1.0.16",
        listOf(
            ChangelogItem(
                "2026-11-01",
                "Vidéo YouTube : bouton plein écran à deux icônes",
                "Le bouton plein écran de la barre du lecteur ne se distinguait pas d'un coin " +
                    "de l'image : un carré vide, à peine plus clair en plein écran, et les " +
                    "commandes du site (CC, roue, ⤢, progression) s'affichaient par-dessus les " +
                    "nôtres dans le même coin une fois la vidéo assez grande — d'où des appuis " +
                    "qui réduisaient la vidéo au lieu de la mettre en pause, et d'autres qui la " +
                    "rétablissaient. Le bouton porte maintenant les deux icônes de " +
                    "l'application (agrandir / réduire) et devient rouge en plein écran, les " +
                    "commandes de YouTube sont masquées, et le mini-lecteur du site ne s'installe " +
                    "plus. Au passage, la lecture et l'arrêt passent par l'API du lecteur YouTube " +
                    "(comme ses boutons) : ils ne déclenchent plus de rechargement de la vidéo, " +
                    "et l'icône lecture/pause suit l'état réel du lecteur.",
                time = "02:00"
            )
        )
    ),
    ChangelogEntry(
        "1.0.15",
        listOf(
            ChangelogItem(
                "2026-10-31",
                "Vidéo YouTube : interface au choix, plein écran natif",
                "Un réglage « Vidéos YouTube » choisit la présentation du lecteur : « Contrôles " +
                    "PressIA » (défaut) garde la page de lecture, où YouTube livre un lecteur " +
                    "sans commandes et où l'image est composée de façon fiable — l'application " +
                    "pose les siennes (lecture/pause, ±10 s, barre, son, vitesses, boucle) ; " +
                    "« Interface YouTube » charge la page d'intégration avec un User-Agent de " +
                    "navigateur, et YouTube affiche alors ses propres contrôles, qui sont de " +
                    "vrais boutons. Le plein écran est désormais celui de l'application : la vue " +
                    "est déplacée sur toute la fenêtre, en paysage, sans recharger la page — donc " +
                    "sans relancer la vidéo —, et ✕ (ou Retour) la remet en place. En mode " +
                    "PressIA, un garde-fou empêche YouTube d'annuler la lecture au retour du " +
                    "plein écran : il ignorait chaque reprise, la vidéo restait bloquée en pause.",
                time = "10:42"
            )
        )
    ),
    ChangelogEntry(
        "1.0.14",
        listOf(
            ChangelogItem(
                "2026-10-31",
                "Article : liens dans le texte, texte sélectionnable",
                "Un lien du corps de l'article passait à la ligne : il formait un bloc à part au " +
                    "milieu d'une phrase. Un conteneur (p, li, titre, citation) est maintenant " +
                    "coulé d'un seul tenant, ses liens n'étant que des plages cliquables — " +
                    "couleur dominante, soulignées — dont le tap ouvre le navigateur. Seul un " +
                    "lien hors de tout paragraphe reste un bloc à part. Au passage, le texte en " +
                    "ligne (gras, italique, span) n'est plus perdu : il n'était ni texte, ni " +
                    "lien, ni média, et disparaissait du paragraphe. Le texte de l'article est " +
                    "par ailleurs sélectionnable (appui long) : poignées et « Copier / Tout " +
                    "sélectionner », sans gêner le balayage d'un article à l'autre.",
                time = "04:17"
            )
        )
    ),
    ChangelogEntry(
        "1.0.13",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Vidéo YouTube : des contrôles qui répondent",
                "Les contrôles du lecteur mobile YouTube sont peints sur un canevas : pause, " +
                    "réglages et barre d'avancée restaient sans effet, quel que soit l'endroit " +
                    "où l'on appuyait. Le lecteur reçoit désormais ses propres contrôles, en " +
                    "éléments réels de la page : un appui sur l'image met en pause ou reprend, " +
                    "un double appui à gauche/droite recule ou avance de 10 s, et une barre en " +
                    "bas donne lecture/pause, temps écoulé et durée, avancée (appui et " +
                    "glissement), son et réglages. Le menu des réglages propose la vitesse de " +
                    "lecture (0,5× à 2×) et la lecture en boucle. Le son reste actif même " +
                    "lorsque le lecteur le coupe de lui-même, sauf si on le coupe depuis la " +
                    "barre.",
                time = "20:50"
            )
        )
    ),
    ChangelogEntry(
        "1.0.12",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Vidéo YouTube : la barre d'avancée avance vraiment",
                "Sur le lecteur mobile, la barre d'avancée est peinte sur un canevas : " +
                    "aucun appui ni glissement ne produisait de recherche (au mieux l'image du " +
                    "temps visé, puis la lecture reprenait au temps précédent), et le document " +
                    "ne contient aucun élément de barre à rendre cliquable. Une barre propre au " +
                    "lecteur est donc injectée dans la page : elle reçoit les gestes directement " +
                    "(et non le navigateur, qui annulait le geste par un `pointercancel` dès " +
                    "quelques pixels de course) et déplace `video.currentTime`. Le bloc vidéo " +
                    "n'est plus peint en noir avant d'avoir sa taille — plus de trame noire sur " +
                    "tout l'écran à l'appui — et un doigt posé sur un lecteur n'est plus pris " +
                    "pour un balayage d'article.",
                time = "19:56"
            )
        )
    ),
    ChangelogEntry(
        "1.0.11",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Démarrage : le filtre « non-lus seuls » ne peut plus planter",
                "Le réglage du filtre était lu dans le constructeur du ViewModel : quand " +
                    "DataStore répond sans suspendre (préférences déjà en mémoire), " +
                    "l'affectation tombait pendant la construction, avant que l'état Compose " +
                    "n'existe — NullPointerException au démarrage. La propriété est déclarée " +
                    "avant le bloc d'initialisation, et la lecture de démarrage est désormais " +
                    "planifiée sur `Dispatchers.Main` sans `.immediate` : elle s'exécute après " +
                    "le constructeur, quel que soit l'ordre des déclarations. `loaded`, affecté " +
                    "par le premier remplissage, est remonté de la même façon.",
                time = "12:45"
            )
        )
    ),
    ChangelogEntry(
        "1.0.10",
        listOf(
            ChangelogItem(
                "2026-10-27",
                "Vidéo : plus de lecture à l'ouverture de l'article",
                "Le lecteur YouTube intégré démarrait tout seul : la page de lecture se lance " +
                    "d'elle-même une fois chargée, et la WebView était créée dès l'affichage de " +
                    "l'article — le son partait avant tout geste. Rien n'est désormais chargé " +
                    "sans appui : le bloc montre l'affiche de la vidéo, et c'est le tap qui " +
                    "ouvre le lecteur. Le délai de secours (20 s) ne court plus qu'à partir de " +
                    "ce tap, sinon il aurait déclaré l'échec pendant que l'affiche était encore " +
                    "affichée. L'affiche et le bouton sont maintenant un seul composant, " +
                    "partagé par le lecteur YouTube, la vidéo `<video>` et le repli « ouvrir " +
                    "sur YouTube ».",
                time = "08:39"
            )
        )
    ),
    ChangelogEntry(
        "1.0.9",
        listOf(
            ChangelogItem(
                "2026-10-26",
                "YouTube : l'image revient ; lecteur natif corrigé",
                "Le lecteur YouTube intégré affichait le son sans l'image : la page " +
                    "d'intégration (`youtube.com/embed/…`) alimente bien le décodeur vidéo, mais " +
                    "aucune image n'atteint l'écran — alors que la page de lecture " +
                    "(`m.youtube.com/watch`), document de premier niveau, rend la vidéo " +
                    "normalement dans la même WebView. C'est aussi ce que fait le tap sur le " +
                    "titre de l'intégration, et c'est ce qui fonctionnait. Le lecteur charge " +
                    "donc la page de lecture, habillée d'une feuille de style injectée qui " +
                    "masque l'en-tête et les contenus annexes et fixe le lecteur en haut : le " +
                    "bloc 16/9 ne montre que la vidéo. La lecture d'erreur lue dans la page, qui " +
                    "faisait basculer sur l'affiche, disparaît (elle se déclenchait à tort et " +
                    "ouvrait l'application YouTube). Enfin, le lecteur `<video>` natif " +
                    "démarrait avant sa préparation — donc jamais : il lance la lecture dans " +
                    "`onPrepared`, et n'est plus créé hors de la fenêtre.",
                time = "00:55"
            )
        )
    ),
    ChangelogEntry(
        "1.0.8",
        listOf(
            ChangelogItem(
                "2026-10-26",
                "Article : barre de défilement ; journal : barre plus fine",
                "L'écran article dessine enfin une barre de défilement au bord droit : un curseur " +
                    "dont la longueur est la part visible de l'article et la position " +
                    "l'avancement (calculés dans la phase de dessin, un défilement ne " +
                    "recompose rien). Largeur 3 dp, dans la marge de 16 dp du texte ; longueur " +
                    "minimale 36 dp pour rester visible sur un très long article ; rien n'est " +
                    "dessiné quand le texte tient à l'écran, ni pendant un balayage (l'icône du " +
                    "geste occupe ce bord). Celle du journal passe de 6 dp à 3 dp, pour le même " +
                    "repère discret.",
                time = "00:32"
            )
        )
    ),
    ChangelogEntry(
        "1.0.7",
        listOf(
            ChangelogItem(
                "2026-10-26",
                "Démarrage : plus de plantage du filtre « non-lus seuls »",
                "Le ViewModel affectait le filtre lu/non-lu avant que son état Compose ne " +
                    "soit créé : quand les préférences étaient déjà en mémoire (processus " +
                    "réveillé par la synchronisation, activité recréée), l'affectation se " +
                    "faisait pendant la construction et l'application plantait " +
                    "(NullPointerException). La propriété est désormais déclarée avant le " +
                    "bloc d'initialisation, avec la règle en commentaire.",
                time = "00:16"
            )
        )
    ),
    ChangelogEntry(
        "1.0.6",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Feedly : les vieux non-lus ne sont plus refermés par la synchro",
                "Feedly marque automatiquement en lu tous les articles non lus de plus de " +
                    "31 jours. L'état lu/non-lu du service n'est donc plus appliqué au-delà " +
                    "de ce délai : un article déjà connu garde son état local (lu ou non lu), " +
                    "et la réconciliation ne ferme plus les non-lus plus vieux que la " +
                    "fenêtre. Un article qui arrive pour la première fois garde l'état " +
                    "annoncé par le serveur.",
                time = "23:52"
            )
        )
    ),
    ChangelogEntry(
        "1.0.5",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Version : un incrément par évolution, pas par compilation",
                "Le numéro de version n'avance plus à chaque compilation : tant que " +
                    "l'évolution en cours n'est pas commitée, elle garde le numéro qu'elle a " +
                    "reçu au premier build. Corriger puis recompiler dix fois ne fait donc " +
                    "plus avancer de dix correctifs ; le commit suivant ouvre la version " +
                    "suivante.",
                time = "22:41"
            )
        )
    ),
    ChangelogEntry(
        "1.0.4",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Session expirée : raccourci « Reconnecter » dans le bandeau",
                "Le message de session expirée porte un bouton « Reconnecter » qui ouvre " +
                    "directement la page de connexion du service du compte concerné (Feedly, " +
                    "Inoreader), sans passer par Comptes ; Instapaper et un identifiant inconnu " +
                    "renvoient à Comptes. Le bandeau reste affiché 12 s au lieu de 4 s pour " +
                    "laisser le temps de toucher le raccourci.",
                time = "01:50"
            ),
            ChangelogItem(
                "2026-10-25",
                "Reconnexion d'un compte récent : synchro normale",
                "Une reconnexion (même compte, nouveau jeton) dont la dernière synchro réussie a " +
                    "moins d'un mois lance la synchronisation normale au lieu de la première " +
                    "synchronisation d'historique : l'historique est déjà en base. Un compte " +
                    "inconnu, ou resté plus d'un mois sans synchro, reprend la passe complète.",
                time = "01:50"
            ),
            ChangelogItem(
                "2026-10-25",
                "À propos : fonctionnalités et règles à jour",
                "Comptes Inoreader, reconnexion sans historique, raccourci de session expirée, " +
                    "recherche locale (titre par défaut, option texte, borne à 200 résultats, " +
                    "marquage annulable), doublons sur tous les non-lus, source des favoris " +
                    "conservée, et nouvelle entrée « Version et journal des modifications ».",
                time = "01:50"
            )
        )
    ),
    ChangelogEntry(
        "1.0.3",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications : barre de défilement",
                "Le journal affiche une barre de défilement au bord droit de la liste : la " +
                    "longueur du curseur indique la part visible des 200 et quelques changements. " +
                    "Elle n'apparaît que si la liste dépasse l'écran.",
                time = "01:41"
            )
        )
    ),
    ChangelogEntry(
        "1.0.1",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications : heure et minute sous chaque date",
                "Chaque changement du journal affiche désormais l'heure du commit (`HH:mm`) sous " +
                    "sa date, ce qui départage deux changements du même jour. L'historique a été " +
                    "régénéré avec ces horaires.",
                time = "01:34"
            )
        )
    ),
    ChangelogEntry(
        "1.0.0",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications",
                "Page accessible en touchant le numéro de version et la date de build (tiroir, " +
                    "À propos). Le numéro de version avance automatiquement à chaque modification " +
                    "de l'application : plus besoin d'y penser.",
                time = "01:29"
            )
        ) + changelogHistory()
    )
)

/**
 * Écran « Journal des modifications » : version courante (celle du build, avec sa date) puis,
 * version par version, les changements repris des messages de commit.
 */
@Composable
fun ChangelogScreen() {
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }
    // Journal (et son historique de 208 entrées) construit **à l'ouverture de cette page**, et
    // seulement retenu tant qu'elle est affichée : la liste repart à la fermeture, rien ne reste
    // en mémoire pour une page consultée une fois.
    val entries = remember { changelog() }
    val total = remember { entries.sumOf { it.items.size } }
    val listState = rememberLazyListState()
    // Texte sélectionnable (mêmes actions que l'écran article : copier, chercher, traduire) :
    // les entrées du journal se citent souvent pour décrire une version.
    SelectableArea {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Text(
                "Journal des modifications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (version.isNullOrBlank()) "Version installée inconnue" else "Version installée : $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$total changement(s) consigné(s), du plus récent au plus ancien",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Toucher le numéro de version (tiroir ou à propos) rouvre cette page.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        // La liste et sa barre de défilement partagent le même espace : la barre se pose au bord
        // droit, sur la marge que le contenu laisse libre (contentPadding de fin), sans masquer
        // le texte.
        Box(Modifier.fillMaxSize()) {
            // Une ligne par changement : la liste reste paresseuse, l'historique fait plus de
            // 200 entrées et tout composer d'un coup serait inutilement coûteux.
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp)
            ) {
                entries.forEach { entry ->
                    item {
                        Text(
                            "Version ${entry.version}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    items(entry.items) { item -> ChangelogRow(item) }
                    item {
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            // Barre de défilement : posée au bord droit, sur la marge de fin (20 dp) du contenu.
            // 3 dp de large, comme celle de l'écran article : un repère de position, pas un
            // élément de décor.
            ChangelogScrollBar(
                listState,
                Modifier.align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = 14.dp, bottom = 32.dp, end = 5.dp)
                    .width(3.dp)
            )
        }
    }
    }
}

/**
 * Barre de défilement verticale du journal (un LazyColumn n'en dessine pas).
 *
 * Le dessin lit l'état de la liste dans la phase de dessin : un défilement ne redessine que la
 * barre, la page n'est pas recomposée. La barre n'apparaît que si la liste dépasse l'écran, et
 * la longueur du curseur reflète la part visible du contenu.
 */
@Composable
private fun ChangelogScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(modifier) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        // Rien à faire défiler : pas de barre.
        if (total <= 0 || visible.isEmpty() || visible.size >= total) return@Canvas
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = CornerRadius(radius, radius)
        )
        // Part visible du contenu, avec un minimum lisible (une ligne très haute ne doit pas
        // réduire le curseur à un trait invisible).
        val thumbHeight = (size.height * visible.size / total)
            .coerceIn(36.dp.toPx().coerceAtMost(size.height), size.height)
        val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
        val first = visible.first()
        // Position : index du premier élément visible, plus la part déjà défilée dedans.
        val inside = if (first.size > 0) {
            (state.firstVisibleItemScrollOffset.toFloat() / first.size).coerceIn(0f, 1f)
        } else 0f
        val maxStart = (total - visible.size).coerceAtLeast(1)
        val progress = ((first.index + inside) / maxStart).coerceIn(0f, 1f)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, maxTop * progress),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

/** Une ligne du journal : date et heure à gauche, titre puis détail du commit à droite. */
@Composable
private fun ChangelogRow(item: ChangelogItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        // Colonne étroite : la date sur une ligne, l'heure juste en dessous. Réduite au strict
        // nécessaire (« 2026-11-06 » en 11 sp ≈ 64 dp) pour laisser la place au texte.
        Column(Modifier.width(70.dp).padding(top = 2.dp, end = 4.dp)) {
            Text(
                item.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!item.time.isNullOrBlank()) {
                Text(
                    item.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            if (!item.detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
