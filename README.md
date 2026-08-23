<div align="center">

# 🔥 Ignite

**Transferencia rápida y directa de archivos en tu red local (LAN).**

Sin internet. Sin servidores externos. Sin la nube.

Punto a punto, entre dispositivos de la misma red Wi-Fi.

<br>

`Android` · `Desktop (JVM)` · `Kotlin Multiplatform` · `Compose Multiplatform`

</div>

---

## ✨ Qué hace

- 🔍 **Descubrimiento automático** — los dispositivos se detectan solos combinando UDP broadcast + mDNS/DNS-SD. Si un firewall o una VPN estorban, entra el plan C: escaneo de la subred `/24`. Abrí la app y aparecen los peers.
- 🤝 **Aprobación antes de recibir** — nadie te manda nada sin permiso: diálogo con **Aceptar / Cancelar / Más tarde**. "Más tarde" deja la conexión abierta 2 minutos con un banner de cuenta atrás.
- 🔢 **PIN de emparejamiento** — código de 6 dígitos que el emisor debe conocer (header `X-Ignite-Pin`). Regenerable desde la app.
- 💚 **Dispositivos confiables** — tras un envío exitoso el PIN queda recordado para ese dispositivo: la próxima vez se precarga solo (candadito 🔒). Podés olvidarlo cuando quieras.
- 📤 **Enviar en un toque** — cola de varios archivos, arrastrar y soltar en Desktop, atajo `Ctrl+O`.
- ⏸️ **Cancelar y reanudar** — cortá un envío o una recepción en cualquier momento; si la app se cerró a mitad de transferencia, al volver te lo avisa y retoma donde quedó.
- ♻️ **Anti-duplicados** — cada archivo tiene un ID estable (`SHA-256` de nombre+tamaño): reintentos y reconexiones no generan copias "(1)", "(2)".
- 📊 **Progreso neón en tiempo real** — barra con degradado verde→cian por archivo y total del lote, bytes transferidos y porcentaje.
- 🕓 **Historial persistente** — todos los envíos y recepciones quedan registrados con Room.
- 🔔 **Notificaciones nativas** — progreso en vivo tanto en Android como en Desktop.
- 🎛️ **Accesibilidad** — respeta "reducir movimiento" del sistema (Android); listas con estados claros de carga/vacío/error y botón Reintentar.
- 🌑 **Look cyberpunk** — verde neón sobre negro puro, tipografía mono, cards redondeadas y logo con vida propia. La identidad del producto, no un modo.

> 🚀 "Ignite" nació para mover archivos grandes entre tus dispositivos sin subirlos a ningún lado.

---

## 🧩 Stack

| Pieza | Tecnología |
|---|---|
| 🧠 Lenguaje | Kotlin 2.4 + Kotlin Multiplatform |
| 🎨 UI | Compose Multiplatform 1.11 |
| 🌐 Red | Ktor 3.5 (servidor embebido + cliente HTTP) |
| 📡 Descubrimiento | UDP broadcast (`48432`) + mDNS + escaneo de subred |
| 💾 Persistencia | Room 2.8 (multiplatform) |
| 💉 DI | Koin 4.2 |
| 🧭 Navegación | Navigation 3 |
| 📁 File picker | FileKit 0.15 |

---

## 🎨 Branding (v1.0)

El logo es la llama diseñada en Inkscape, trazada a vectores y convertida 1:1 a Compose:

- **Pipeline**: el SVG (`tools/logo.svg`) se procesa con `tools/svg_to_compose.py`, que genera `FlameTraceMark` — ignora el raster incrustado, descarta los fondos blancos del trazado y pinta toda la silueta con un único degradado continuo (lima `#B2EB63` → verde bosque), sin bandas ni grietas.
- **Logo vivo**: respira anclada a la base, el brillo pulsa con la respiración y un barrido de luz recorre la llama cada ~3s. `FlameTraceMark(animate = false)` la congela.
- **Preview**: `./gradlew :desktopApp:run -DmainClass=com.andyl.ignite.BrandPreviewKt` muestra el logo y las marcas alternativas a todos los tamaños.

---

## 🚀 Correrlo

```bash
# Desktop (JVM)
./gradlew :desktopApp:run

# Desktop con hot reload
./gradlew :desktopApp:hotRun --auto

# Android (APK debug)
./gradlew :androidApp:assembleDebug
```

Instalá el APK en un dispositivo y ejecutá Desktop en tu máquina (o dos teléfonos en la misma Wi-Fi) → se descubren solos → enviá un archivo.

### ⚠️ Para probar entre dispositivos

- **Misma red local** (misma subred Wi-Fi).
- Android: la app pide permisos de red/wi-fi (declarados en el `AndroidManifest.xml`). Concedelos si el sistema los pide.
- El puerto HTTP es `48213` y el de descubrimiento `48432`; si algún firewall de SO bloquea conexiones entrantes, abrilos.
- Si aun así no se ven (VPN, AP isolation), usá **Conectar por IP**: en Windows `ipconfig → IPv4`, en Mac `ifconfig | grep inet`.
- Los archivos recibidos en Desktop van a:
  - macOS: `~/Library/Application Support/com.andyl.ignite/received`
  - Windows: `%APPDATA%\com.andyl.ignite\received`
  - Linux: `~/.local/share/com.andyl.ignite/received`
- En Android se guardan en la carpeta de descargas configurada (elegible desde el perfil).

---

## 🔐 Seguridad y privacidad

- **Cero nube**: todo viaja directo por tu LAN; ningún byte sale a internet.
- **Autorización doble**: el receptor aprueba cada transferencia y valida el PIN del emisor.
- **Confianza explícita**: recordar un PIN es una decisión tuya por dispositivo, reversible desde la lista.
- TLS con certificado self-signed queda como *upgrade path* documentado (`TlsConfig`) para blindar contra sniffing pasivo en redes compartidas.

---

## 🏗️ Arquitectura

```
com.andyl.ignite
├── App.kt                    # raíz Compose (theme + Navigation 3)
├── di/                       # Koin: appModule + platformModule (expect/actual)
├── domain/                   # modelos y contratos puros
│   ├── model/                # Device, Transfer, Beacon
│   ├── DeviceDiscovery.kt    # contrato de descubrimiento
│   ├── FileSender.kt         # envío con Flow<Float> de progreso
│   ├── FileReceiver.kt       # recepción con aprobación y progreso
│   ├── PairingManager.kt     # PIN de emparejamiento (expect/actual)
│   ├── TrustedDevices.kt     # PIN recordado por dispositivo
│   └── TransferRepository.kt # historial (Room)
├── data/
│   ├── network/
│   │   ├── UdpDeviceDiscovery.kt      # broadcast UDP
│   │   ├── MdnsDeviceDiscovery.kt     # DNS-SD
│   │   ├── CompositeDeviceDiscovery.kt # merge de estrategias
│   │   ├── SubnetScannerDiscovery.kt  # fallback /24 anti-VPN
│   │   ├── KtorFileSender.kt          # reintentos, resume, uploadId
│   │   ├── KtorFileReceiver.kt        # aprobación, TTLs, anti-dupes
│   │   └── TlsConfig.kt               # upgrade path HTTPS
│   ├── db/                   # Room: entity, dao, database (por plataforma)
│   ├── notification/         # notificaciones nativas (expect/actual)
│   ├── MotionSettings.kt     # "reducir movimiento" (#30)
│   └── Platform.kt           # AppStorage / DeviceInfo (expect/actual)
└── presentation/             # UI (MVI: Event / State / Effect)
    ├── MviViewModel.kt
    ├── navigation/
    ├── home/                 # card héroe de transmisión + dispositivos + recepción
    ├── history/
    ├── branding/             # FlameTraceMark (logo generado desde SVG) + marcas
    └── theme/                # paleta cyberpunk verde neón / negro
```

Detalle del patrón MVI y el flujo de transferencia en [`docs/architecture.md`](./docs/architecture.md).

---

## 🧪 Tests

```bash
./gradlew :shared:jvmTest
```

Cubre el pipeline completo: enviar → recibir (verifica contenido idéntico y progreso 100%),
detección de pares vía beacon UDP, recordatorio de PIN por dispositivo (`TrustedDevices`)
y lógica del ViewModel (aprobaciones diferidas, olvidar dispositivo, reanudación).

---

## 🗺️ Roadmap

- [ ] 📱 Target iOS (mDNS/NSD + servidor nativo)
- [ ] 🗃️ Room persistente en Desktop (driver nativo)
- [ ] 📁 Enviar carpetas completas
- [ ] 🔐 TLS punto a punto con certificado self-signed + fingerprint pinning
- [ ] 📷 Emparejar por QR (PIN + fingerprint)
- [ ] 🌍 i18n (strings a recursos CMP)

---

## 📄 Licencia

MIT
