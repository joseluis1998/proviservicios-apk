# Proviservicios APK

Aplicacion Android tipo WebView para abrir:

https://provi.gobiernodigital.site/

## Requisitos para compilar

- Android Studio instalado.
- Internet la primera vez para que Gradle descargue el plugin Android.

## Como generar el APK

### Opcion 1: GitHub Actions

1. Crear un repositorio nuevo en GitHub.
2. Subir todo el contenido de esta carpeta al repositorio.
3. Entrar en la pestana `Actions`.
4. Abrir el flujo `Build APK`.
5. Si no se ejecuta solo, presionar `Run workflow`.
6. Al terminar, descargar el artefacto `proviservicios-debug-apk`.

El archivo descargado contiene:

`app-debug.apk`

### Opcion 2: Android Studio

1. Abrir Android Studio.
2. Seleccionar `Open`.
3. Abrir esta carpeta: `C:\Users\PC\Documents\codex\provi_android_apk`.
4. Esperar que termine `Gradle Sync`.
5. Ir a `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
6. El APK quedara en:

`app\build\outputs\apk\debug\app-debug.apk`

## Permisos incluidos

- Internet.
- Camara.
- Ubicacion fina y aproximada.
- Estado de red.
- Notificaciones en Android 13+.

## Notas

- La app abre el sistema publicado en Hostinger.
- El modo offline depende del sistema web: el tecnico debe iniciar sesion una vez con internet en el celular.
- La camara y ubicacion se solicitan como permisos nativos de Android.
