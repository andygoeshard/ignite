# Ignite — Roadmap v1.x

> Diferenciación frente a LocalSend / AirDrop · decidido en brainstorm con el dueño del producto (ago 2026).
> Prioridad de plataformas: **Android + Desktop** (el dúo más desatendido, el mayor mercado huérfano de AirDrop).

## Posicionamiento

- **LocalSend**: cross-platform pero burocrático — diálogo por cada archivo, recepción exige ventana abierta, envíos secuenciales.
- **AirDrop**: mágico (cero fricción entre dispositivos propios) pero exclusivo de Apple.
- **Ignite apunta a**: la magia de AirDrop para el resto del mundo, con capacidades que ninguno tiene, sin nube jamás.

## Veredictos del dueño

| Idea | Estado |
|---|---|
| QR pairing **bidireccional** (emparejar de un lado ⇒ enviar/recibir de ambos lados) | ✅ Fase 1 |
| Política por dispositivo: Preguntar / Auto-aceptar / Silencioso + "no volver a preguntar" | ✅ Fase 1 |
| Thumbnail preview al aprobar entrada | ✅✅ Fase 1 |
| Fan-out 1 → muchos | ✅ Fase 3 |
| Clipboard universal | ✅ (+10) Fase 3 |
| Push de texto/notas rápidas | ✅ Fase 3 |
| Verificación criptográfica visible (recibo SHA-256) | ✅ Fase 3 |
| Watch folders (Desktop **y** Android) | ✅ (+10) Fase 2 |
| Auto-backup de cámara → PC | ✅ Fase 2 |
| Recepción invisible en Desktop (tray + drop zone) | ✅ Fase 2 |
| **Delta sync** (rsync-lite: solo viajan los bytes cambiados) | 🔥🔥 Fase 4 ("DE UNA AMIGO") |
| Chunks paralelos + benchmark in-app | ✅ Fase 4 (comparte infraestructura con delta sync) |
| Modo sobrevivencia (AP isolation / hotspot / USB) | ✅ como guía + detección (no es imposible: hotspot del celu elimina al router del medio; USB tethering = interfaz directa) |
| Llama-viva-por-throughput (el logo arde según velocidad real) | 👀 prototipar en BrandPreview antes de decidir |
| CLI + modo servicio | ⏳ futuro |
| Salas temporales | ❌ descartado |

---

## Fase 1 — Confianza bidireccional (COMPLETADA 2026-08-23)

Todo el paquete toca el mismo flujo: emparejamiento/aprobación.

**Estado real:** 1a–1d implementadas y probadas (`./gradlew :shared:allTests` verde, incluye test del handshake `/pair` y del preview). Detalles de desvío respecto al plan original:

- **1a** ✅ Headers `X-Ignite-Device-Id` / `X-Ignite-Device-Name` en `/upload`; `AwaitingApproval` lleva `peerDeviceId` (+ `peerDeviceName` opcional).
- **1b** ✅ `TrustPolicy` (ASK/AUTO/SILENT) persistida por dispositivo; gate en receptor; chip de política en la lista de dispositivos; botón "Aceptar siempre" en el diálogo.
- **1c** ✅ `POST /preview?uploadId=&pin=` con cache TTL 30s; thumbnail expect/actual en emisor (imágenes); polling desde el diálogo con timeout.
- **1d** ⚠️ parcial: render QR propio (generado in-process, sin qrose) + escaneo con `zxing-android-embedded` (Desktop no escanea en v1) + handshake `POST /pair?pin=` que otorga confianza mutua AUTO. Pendiente: permiso CAMERA en runtime (el manifest ya lo declara; zxing pide el permiso solo).

### 1a. Identidad del emisor en el protocolo (prerequisito de todo) — HECHO

Hoy el receptor NO sabe quién le manda: `POST /upload` solo llega con IP (`KtorFileReceiver.kt:241`) y el diálogo muestra nombre resuelto si el peer fue descubierto. Cambios:

- `KtorFileSender.executeUpload` (~`:161-170`): agregar headers `X-Ignite-Device-Id` y `X-Ignite-Device-Name` (constantes junto a las existentes `:198-205`).
- `KtorFileReceiver` routing `/upload`: leer esos headers; extender `IncomingEvent.AwaitingApproval` (`FileReceiver.kt:39-44`) con `peerDeviceId`/`peerDeviceName`.
- Plombeo MVI: `HomeContract.IncomingUi.AwaitingApproval` + mapeo en `HomeViewModel`.

### 1b. Política por dispositivo — HECHO

Hoy la confianza es binaria (PIN recordado o no) y `requiresApproval` es un bool global fijo (`AppModule.kt:48-57`).

- Modelo: `TrustedDevice` gana campo `policy: TrustPolicy = ASK` (`TrustedDevices.kt:12-18`; JSON con valor default para no romper archivos existentes).
  - `ASK` = flujo actual · `AUTO` = sin diálogo, notificación de progreso normal · `SILENT` = sin diálogo ni notificación (solo historial).
- Gate en receptor: reemplazar chequeo global por lookup de política vía deviceId entrante (`KtorFileReceiver.kt:259-321`). Sin identidad conocida ⇒ ASK.
- UI: selector de política por dispositivo (lista de dispositivos confiables / perfil) + acción "Aceptar siempre de este dispositivo" en el diálogo de aprobación (= TrustedDevices.remember + policy AUTO). Ese es EL momento AirDrop.

### 1c. Thumbnail en aprobación — HECHO

- Nuevo endpoint `POST /preview?uploadId=&pin=` en receptor (cachea bytes en memoria por uploadId, TTL corto).
- Emisor genera miniatura (expect/actual `createThumbnail(path, maxPx): ByteArray?`, máx ~64KB, solo imágenes/video-frame) y la manda justo antes del `/upload`; si falla, sigue igual (icono genérico en el diálogo).
- Render: bloque `text = {}` de `IncomingApprovalDialog` (`HomeEntry.kt:167-181`).

### 1d. QR bidireccional — HECHO (Desktop muestra, Android escanea)

- Payload QR: `{deviceId, name, host, port, pin}` (JSON compacto).
- Render QR en Desktop (y Android): librería KMP nueva (candidato: `qrose`). Escaneo: `zxing-android-embedded` en `androidApp` (solo el celular escanea en v1).
- Handshake nuevo `POST /pair`: valida PIN, guarda al emisor como confiable (policy AUTO), devuelve identidad propia ⇒ emisor guarda al receptor. Resultado: emparejado desde UN lado ⇒ los dos lados envían y reciben sin PIN ni diálogos.
- Mantener PIN manual como fallback.

### Estado de la investigación (hechos del código)

- `TrustedDevices`: JSON plano vía readRaw/writeRaw (`trusted_devices.json` en filesDir); API `remember/forget/pinFor/isTrusted`.
- Aprobación: gate único en `KtorFileReceiver.kt:259-321` con `CompletableDeferred` + ventana 120s; decisión vía `decideApproval()` (`FileReceiver.kt:68`).
- `Beacon` = `{deviceId, deviceName, port}`; deviceId = UUID persistido en `device_id` (estable por instalación).
- Room deshabilitado en ambas plataformas (dao Noop en memoria) — el historial no sobrevive reinicios; no bloquea Fase 1.
- No hay ninguna dependencia QR hoy (libs.versions.toml limpio).
- DI central en `di/AppModule.kt:34-74` + platformModule expect/actual.

---

## Fase 2 — Infraestructura silenciosa

**Estado:** 2a HECHA (2026-08-23) — tray desktop + drop zone + pausa sincronizada (UI ⏻ ↔ tray ↔ notificación Android vía `ReceiverController`). Pausado en Android = sin notificación en la barra; pausado en desktop = sin ícono en el menú.

- ✅ Tray icon + ocultar-al-cerrar en Desktop (recibir sin abrir ventana).
- ✅ Drop zone: ventana chiquita siempre-on-top; soltar archivos los agrega a la cola y envía al dispositivo seleccionado (`DropChannel` → `HomeViewModel.onExternalDrop`).
- ⏳ Watch folders con reglas simples (Desktop + Android): "lo que caiga acá va a X dispositivo".
- ⏳ Auto-backup cámara → PC: al detectarse la PC confiable en red, subir fotos nuevas (consentimiento una vez, después invisible).

Depende de: políticas por dispositivo (sin auto-trust esto sería peligroso).

## Fase 3 — Contenido nuevo

- Push de texto/notas rápidas (canal de control existente, mensaje pequeño sin archivo).
- Clipboard sync bidireccional con historial.
- Fan-out 1→N: mismo stream hacia múltiples receptores simultáneos (LocalSend manda secuencial — nicho aulas/eventos/rodajes).
- Carpetas completas con estructura preservada (manifest de árbol, progreso por carpeta).
- Recibo SHA-256 visible post-transferencia ("integridad verificada").

## Fase 4 — El foso técnico

- **Delta sync rsync-lite**: negociación de bloques (rolling hash) ⇒ re-enviar un proyecto de 2GB modificado mueve solo los bytes cambiados. Nadie en el espacio lo tiene.
- **Chunks paralelos**: partir archivo en N trozos por conexiones simultáneas (2-4x real en Wi-Fi 5/6) + pantalla de benchmark in-app ("Ignite: XX MB/s").

Ambos comparten la infraestructura de trozado — van juntos.

## Estacionado

- Llama-viva-por-throughput: prototipo en BrandPreview (`animate` ya existe para colgar intensidad/barrido a la velocidad medida).
- Modo sobrevivencia: detección de AP isolation + guía de hotspot/USB tethering (dos caminos reales, cero magia).
- CLI + modo servicio (`ignite send ...`, curl-friendly).
- Cast local (video celu → laptop con Range requests).
