# Haval Launcher (Haval H3 2011)
0. Compatible con Android 10 (API 29) o superior.
1. Abre la carpeta en Android Studio y espera la sincronización de Gradle.
2. Ejecuta en la tablet del auto y pulsa Inicio > "Haval Launcher" > "Siempre".
3. Permisos: ubicación (GPS), notificaciones (música) y Bluetooth (OBD2).
4. Tema: automático por hora (amanecer / día / atardecer / noche). El icono 🌗 arriba a la derecha
   cambia entre automático, claro y oscuro.
5. OBD2: empareja primero tu adaptador ELM327 en Ajustes > Bluetooth, luego Fallas > Conectar OBD2.
   Con el adaptador conectado, velocidad, rpm y temperatura salen del auto.
6. Imagen del auto: reemplaza res/drawable/haval_h3.xml por tu PNG en res/drawable-nodpi/haval_h3.png.

## Compilar el APK en GitHub (sin Android Studio)
1. Sube todo este proyecto a un repositorio de GitHub (rama main).
2. Entra a la pestaña Actions > "Compilar APK" (si no arranca solo: Run workflow).
3. Cuando termine (unos 3-5 min), abre la ejecución y descarga "HavalLauncher-apk" en Artifacts.
4. Descomprime y instala app-debug.apk en la tablet (Android 10 o superior).
