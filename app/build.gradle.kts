plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Indispensable : sans lui aucun @Serializable n'a de serializer généré et les appels
    // Retrofit (Feedly, Inoreader) échouent au runtime avec « Serializer not found ».
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pressia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pressia"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // OAuth placeholders — surchargeables via local.properties ou variables d'env.
        // Feedly : https://feedly.com/v3/auth/dev — renseignez FEEDLY_CLIENT_ID / FEEDLY_CLIENT_SECRET
        // Inoreader : https://www.inoreader.com/developers/register-app
        buildConfigField("String", "FEEDLY_CLIENT_ID", "\"${project.findProperty("FEEDLY_CLIENT_ID") ?: System.getenv("FEEDLY_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "FEEDLY_CLIENT_SECRET", "\"${project.findProperty("FEEDLY_CLIENT_SECRET") ?: System.getenv("FEEDLY_CLIENT_SECRET") ?: ""}\"")
        buildConfigField("String", "FEEDLY_REDIRECT_URI", "\"pressia://oauth/feedly\"")
        buildConfigField("String", "INOREADER_APP_ID", "\"${project.findProperty("INOREADER_APP_ID") ?: System.getenv("INOREADER_APP_ID") ?: ""}\"")
        buildConfigField("String", "INOREADER_APP_KEY", "\"${project.findProperty("INOREADER_APP_KEY") ?: System.getenv("INOREADER_APP_KEY") ?: ""}\"")
        buildConfigField("String", "INOREADER_REDIRECT_URI", "\"pressia://oauth/inoreader\"")
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

    // WorkManager (sync planifiée)
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
