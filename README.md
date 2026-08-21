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

- 🔍 **Descubrimiento automático** — los dispositivos se anuncian y se detectan solos vía UDP broadcast. Abrí la app y aparecen los peers de la red.
- 📤 **Enviar en un toque** — seleccionás un archivo (o lo arrastrás en Desktop) y elegís el dispositivo destino.
- 📥 **Recibir directo** — servidor HTTP embebido en cada dispositivo; los archivos se guardan en local.
- 📊 **Progreso en tiempo real** — barra de progreso por transferencia.
- 🕓 **Historial** — registro de envíos y recepciones persistido con Room.
- 🖥️ **Drag & drop** en Desktop.

> 🚀 "Ignite" nació para mover archivos grandes entre tus dispositivos sin subirlos a ningún lado.

---

## 🧩 Stack

| Pieza | Tecnología |
|---|---|
| 🧠 Lenguaje | Kotlin 2.4 + Kotlin Multiplatform |
| 🎨 UI | Compose Multiplatform 1.11 |
| 🌐 Red | Ktor 3.5 (servidor embebido + cliente HTTP) |
| 📡 Descubrimiento | UDP broadcast (puerto `48432`) |
| 💾 Persistencia | Room 2.8 (multiplatform) |
| 💉 DI | Koin 4.2 |
| 🧭 Navegación | Navigation 3 |
| 📁 File picker | FileKit 0.15 |

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
- El puerto HTTP es `48213`; si algún firewall de SO bloquea conexiones entrantes, abrí ese puerto.
- Los archivos recibidos en Desktop van a:
  - macOS: `~/Library/Application Support/com.andyl.ignite/received`
  - Windows: `%APPDATA%\com.andyl.ignite\received`
  - Linux: `~/.local/share/com.andyl.ignite/received`

---

## 🏗️ Arquitectura

```
com.andyl.ignite
├── App.kt              # raíz Compose (theme + Navigation 3)
├── di/                 # Koin: appModule + platformModule (expect/actual)
├── domain/             # modelos y contratos puros
│   ├── model/          # Device, Transfer, Beacon
│   ├── DeviceDiscovery.kt
│   ├── FileSender.kt   # envío con Flow<Float> de progreso
│   ├── FileReceiver.kt # recepción con progreso
│   └── TransferRepository.kt
├── data/               # implementaciones
│   ├── network/        # Ktor sender/receiver + UDP discovery
│   ├── db/             # Room: entity, dao, database (por plataforma)
│   └── Platform.kt     # AppStorage / DeviceInfo (expect/actual)
└── presentation/       # UI (MVI: Event / State / Effect)
    ├── MviViewModel.kt
    ├── navigation/
    ├── home/           # radar + selección + envío + progreso
    ├── history/
    └── theme/
```

Detalle del patrón MVI y el flujo de transferencia en [`docs/architecture.md`](./docs/architecture.md).

---

## 🧪 Tests

```bash
./gradlew :shared:jvmTest
```

Cubre el pipeline completo: enviar → recibir (verifica contenido idéntico y progreso 100%) y
detección de pares vía beacon UDP.

---

## 🗺️ Roadmap

- [ ] 📱 Target iOS (mDNS/NSD + servidor nativo)
- [ ] 🗃️ Room persistente en Desktop (driver nativo)
- [ ] 📁 Enviar múltiples archivos y carpetas
- [ ] ⏸️ Cancelar / reanudar transferencias
- [ ] 🔐 Cifrado opcional punto a punto
- [ ] 🖥️ Pantalla de "recibir" con aceptar/rechazar

---

## 📄 Licencia

MIT