# Analyse Sécurité Réseau PressIA

## Problème identifié

**Fichier** : `app/src/main/AndroidManifest.xml`
**Ligne manquante** : `android:usesCleartextTraffic="true"` dans `<application>`

## Contexte

- **targetSdk** : 34 (Android 14)
- **OkHttp version** : 4.12 (incluse dans les outils d'OpenCode)
- **Comportement** : Refus silencieux des requêtes HTTP en clair (`http://` au lieu de `https://`)

## Pourquoi ça casse

Avant Android 7 (targetSdk < 26), OkHttp acceptait le HTTP par défaut.
À partir d'Android 9 (targetSdk 28+), le HTTP est découragé mais encore permis avec avertissement.
À partir d'Android 14 (targetSdk 34), **OkHttp 4.12 bloque purement** les requêtes HTTP en clair sans configuration explicite.

Résultat : `LocalRssFetcher.fetch()` retourne `emptyList()` sans erreur visible. L'UI affiche "Flux ajouté et synchronisé" mais 0 article. Le serveur hôte ne voit aucune requête.

## Preuve dans le code

`LocalRssFetcher.kt` ligne 23 :
```kotlin
val req = Request.Builder().url(feedUrl).header("User-Agent", UA).build()
```

Si `feedUrl` commence par `http://`, la requête sera bloquée par la politique réseau d'Android 14 sans `usesCleartextTraffic`.

## Solutions

### Solution A : Manifest (rapide, pour ce projet)

Modifier `AndroidManifest.xml` ligne 18 (balise `<application>`) :
```xml
android:usesCleartextTraffic="true"
```

### Solution B : network_security_config (plus propre, recommandée pour production)

1. Créer `res/xml/network_security_config.xml` :
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors include="system" />
    </base-config>
</network-security-config>
```

2. Dans `AndroidManifest.xml` :
```xml
android:networkSecurityConfig="@xml/network_security_config"
```

Avantage Solution B : Plus granulaire, ne concerner que la sécurité réseau sans "autoriser tout le cleartext" en une ligne.

## Action immédiate

1. Vérifier `AndroidManifest.xml` - la ligne `android:usesCleartextTraffic` est absente
2. L'ajouter via Solution A ou B
3. Reconstruire l'émulateur : `./gradlew :app:assembleDebug --offline`
4. Tester avec un flux RSS local en HTTP : les requêtes doivent apparaître

## Autres implications

Une fois corrigé, on pourra tester :
- Le fetching de flux RSS locaux en HTTP (non recommandé en prod mais utile pour test)
- Le comportement avec des flux HTTPS (toujours préférable)
- La différence entre `usesCleartextTraffic="true"` et `network_security_config`