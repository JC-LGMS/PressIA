plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Indispensable : sans lui aucun @Serializable n'a de serializer généré et les appels
    // Retrofit (Feedly, Inoreader) échouent au runtime avec « Serializer not found ».
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.security.MessageDigest

/*
 * Version automatique (voir AGENTS.md, « Version et journal des modifications »).
 *
 * L'empreinte des sources de l'application (`src/main` + ce script) est comparée à celle
 * enregistrée dans `version.properties` : si elle a changé, le correctif (1.0.x) et
 * `versionCode` sont incrémentés **avant** la lecture par `defaultConfig`.
 *
 * Un incrément = une **évolution terminée**, pas une compilation. Pendant qu'on travaille on
 * recompile plusieurs fois (corriger, rebuilder, revérifier) : la version ne doit avancer
 * qu'une fois. Le signal est le commit — `version.properties` est le seul fichier qui porte la
 * version, donc **tant que ce fichier n'est pas commité, le bump en cours est celui de
 * l'évolution en cours** et on ne l'incrémente plus. Après le commit, le fichier redevient
 * propre et la modification suivante ouvre la version suivante.
 *
 * L'empreinte est en revanche réécrite **à chaque** build, même sans incrément : le fichier
 * commité doit décrire exactement les sources commitées, sinon le build suivant prendrait le
 * travail déjà livré pour une nouvelle modification et incrémenterait à tort.
 */
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}

/** `version.properties` porte-t-il un incrément non commité (évolution en cours) ? */
fun versionFileIsDirty(): Boolean = try {
    val proc = ProcessBuilder("git", "diff", "--quiet", "HEAD", "--", versionFile.name)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    // Vider la sortie avant d'attendre : un tampon plein bloquerait le processus.
    proc.inputStream.readBytes()
    proc.waitFor() == 1
} catch (e: Exception) {
    // Pas de git (ou pas de dépôt) : on retombe sur l'ancien comportement, un incrément par
    // modification constatée.
    false
}
val sourceHash = run {
    val md = MessageDigest.getInstance("SHA-256")
    val base = projectDir
    fileTree(base) {
        include("src/main/**")
        include("build.gradle.kts")
    }.files.sortedBy { it.relativeTo(base).path }.forEach { f ->
        md.update(f.relativeTo(base).path.toByteArray(Charsets.UTF_8))
        md.update(0.toByte())
        md.update(f.readBytes())
        md.update(0.toByte())
    }
    md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
val storedSourceHash = versionProps.getProperty("sourceHash", "")
var appVersionCode = versionProps.getProperty("versionCode", "1").toIntOrNull() ?: 1
var appVersionName = versionProps.getProperty("versionName", "1.0.0")
val sourcesChanged = storedSourceHash.isNotBlank() && storedSourceHash != sourceHash
if (sourcesChanged) {
    if (versionFileIsDirty()) {
        // Évolution en cours : la version a déjà été incrémentée pour ce travail, on la garde.
        logger.lifecycle(
            "PressIA : sources modifiées, version $appVersionName ($appVersionCode) conservée " +
                "(incrément en attente de commit)"
        )
    } else {
        appVersionCode += 1
        val parts = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
        while (parts.size < 3) parts += 0
        parts[2] += 1
        appVersionName = parts.take(3).joinToString(".")
        logger.lifecycle("PressIA : modification terminée, nouvelle version $appVersionName ($appVersionCode)")
    }
}
if (storedSourceHash != sourceHash || versionProps.getProperty("versionName") != appVersionName) {
    versionFile.writeText(
        "versionCode=$appVersionCode\nversionName=$appVersionName\nsourceHash=$sourceHash\n",
        Charsets.UTF_8
    )
}

android {
    namespace = "com.pressia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pressia"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        // OAuth placeholders - surchargeables via local.properties ou variables d'env.
        // Utiles seulement au flux OAuth générique (Inoreader via OAuthWebViewScreen).
        // Feedly : https://feedly.com/v3/auth/dev - renseignez FEEDLY_CLIENT_ID / FEEDLY_CLIENT_SECRET
        // uniquement si vous passez par ce flux ; la connexion Feedly normale (FeedlySignInScreen :
        // page officielle intégrée, jeton de session récupéré) utilise le client_id=feedly public.
        // Inoreader : https://www.inoreader.com/developers/register-app
        //
        // Identifiants Inoreader par défaut : ceux de l'extension officielle du navigateur
        // (Inoreader Browser Extension, « Innologica »), identiques dans tous ses bundles :
        //   APP_ID  = 1000001524
        //   APP_KEY = uxILTlPQilTphfcf4xwBEspL_0EDaUZL
        // Ils sont publics par construction (l'extension est du JavaScript côté client) et
        // servent d'« authentification applicative » : l'API les exige sur CHAQUE appel,
        // à côté de l'authentification utilisateur. Sans eux, Inoreader répond 403.
        // Un compte de l'application (identifiants déclarés par le développeur) reste
        // prioritaire : renseignez INOREADER_APP_ID / INOREADER_APP_KEY dans
        // local.properties ou l'environnement pour le remplacer.
        buildConfigField("String", "FEEDLY_CLIENT_ID", "\"${project.findProperty("FEEDLY_CLIENT_ID") ?: System.getenv("FEEDLY_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "FEEDLY_CLIENT_SECRET", "\"${project.findProperty("FEEDLY_CLIENT_SECRET") ?: System.getenv("FEEDLY_CLIENT_SECRET") ?: ""}\"")
        buildConfigField("String", "FEEDLY_REDIRECT_URI", "\"pressia://oauth/feedly\"")
        buildConfigField("String", "INOREADER_APP_ID", "\"${project.findProperty("INOREADER_APP_ID") ?: System.getenv("INOREADER_APP_ID") ?: "1000001524"}\"")
        buildConfigField("String", "INOREADER_APP_KEY", "\"${project.findProperty("INOREADER_APP_KEY") ?: System.getenv("INOREADER_APP_KEY") ?: "uxILTlPQilTphfcf4xwBEspL_0EDaUZL"}\"")
        buildConfigField("String", "INOREADER_REDIRECT_URI", "\"pressia://oauth/inoreader\"")
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.browser:browser:1.8.0")

    // Compose Material3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room (offline)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Paging + DataStore
    implementation("androidx.paging:paging-runtime:3.3.2")
    implementation("androidx.paging:paging-compose:3.3.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager : conservé uniquement pour annuler le travail planifié hérité
    // d'anciennes versions (plus rien ne planifie, cf. PressIAApp).
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Réseau
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Images offline
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Contenu article (Readability maison via Jsoup)
    implementation("org.jsoup:jsoup:1.17.2")

    // Tests
    testImplementation("junit:junit:4.13.2")
    // org.json n'existe que comme stub Android dans les tests unitaires JVM : la bibliothèque
    // réelle est nécessaire pour tester le registre de comptes (et la sauvegarde).
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
