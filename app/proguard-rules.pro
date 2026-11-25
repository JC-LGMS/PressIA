# PressIA
# Noms conservés (messages d'erreur lisibles, `javaClass.simpleName`), mais R8 peut **élaguer et
# renommer** le code de l'application : `-keep` annulait tout rétrécissement sur com.pressia. Les
# sérialiseurs kotlinx sont protégés par les règles embarquées de la bibliothèque ; une release
# doit être testée après toute modification de ce fichier (synchro + import JSON).
-keepnames class com.pressia.**
-dontwarn org.jsoup.**
