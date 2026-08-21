# Arquitectura de Ignite

Este documento describe el diseño del código base: paquetes, patrón MVI, flujo de transferencia de
archivos y decisiones de stack. Es la guía para sumar funcionalidad sin romper los contratos.

## Capas

```
┌───────────────────────────────────────────────────────────┐
│ presentation  UI + MVI (Event / State / Effect)            │
│   navigation, home, history, theme                         │
├───────────────────────────────────────────────────────────┤
│ di           Koin (appModule común + platformModule)       │
├───────────────────────────────────────────────────────────┤
│ domain       contratos puros (sin dependencias de Ktor)    │
│   models     Device, Transfer, Beacon                      │
│   services   DeviceDiscovery, FileSender, FileReceiver     │
│   repos      TransferRepository                            │
├───────────────────────────────────────────────────────────┤
│ data         implementaciones: Ktor, UDP, Room, storage    │
└───────────────────────────────────────────────────────────┘
```

Regla de dependencia: `presentation` → `domain` → `data`. El dominio no conoce Ktor ni Room; las
implementaciones se inyectan con Koin.

## Patrón MVI

Base en `presentation/MviViewModel.kt`: cada pantalla define tres tipos y un ViewModel.

```kotlin
sealed interface HomeEvent                        // input: acciones del usuario
data class HomeState(val devices: List<Device>)   // estado inmutable de la pantalla
sealed interface HomeEffect                        // one-shot: snackbar, navegar
```

- **State** — `StateFlow<State>`, inmutable, expuesto vía `vm.state`.
- **Effect** — `Channel<Effect>` (BUFFERED), consumido con `LaunchedEffect(vm) { vm.effect.collect { } }`.
- **Event** — único punto de entrada `vm.onEvent(event)`; el ViewModel decide: actualizar state,
  lanzar corrutina o emitir effect.

Convenciones:

- El composable **nunca** decide reglas de negocio: solo dispara `onEvent`.
- La UI es stateless: `HomeScreen(state, onEvent, ...)`; el `HomeEntry` obtiene el VM con
  `koinViewModel()`, colecta `state` con `collectAsStateWithLifecycle()` y los `effect`.
- Estado calculable no se guarda redundante (p. ej. `canSend` es `get()` derivado).

### Ciclo de una transferencia saliente

```
Usuario: selecciona archivo (picker o drag & drop)
  → HomeEvent.OnFileSelected(PendingFile)
  → estado: pendingFiles += file

Usuario: pulsa "Enviar"
  → HomeEvent.OnSendClick
  → send(): upsert Transfer(IN_PROGRESS) en Room
  → sender.send(target, path, name, size)
        → Flow<Float> con progreso
  → cada emisión: updateState(progress) + upsert(progress)
  → al terminar: upsert(COMPLETED) + effect ShowMessage
  → en error: upsert(FAILED) + effect ShowMessage
```

### Ciclo de una transferencia entrante

```
KtorFileReceiver (servidor embebido, puerto 48213)
  → POST /upload (multipart)
  → KtorFileReceiver.upload: PartData.FileItem
  → stream a disco + _receivedTransfers.tryEmit(progreso)
  → HomeViewModel colecta receiver.receivedTransfers y lo refleja en estado/effects
```

## Descubrimiento UDP

`UdpDeviceDiscovery` (`data/network`):

- **Anuncio**: cada 2 s envía un `Beacon` (deviceId, deviceName, port) a `255.255.255.255:48432`.
- **Escucha**: recibe beacons y expone cada peer en `devices: SharedFlow<Device>`.
- Filtra el propio `deviceId` para no auto-detectarse.
- `DeviceInfo` (expect/actual) provee id estable (persistido en archivo) y nombre legible.

> Swappable: la interfaz `DeviceDiscovery` permite reemplazar UDP por mDNS/DNS-SD sin tocar la UI.

## Stack y decisiones

| Pieza | Elección | Por qué |
|---|---|---|
| Targets | Android + Desktop (JVM) | El servidor Ktor es JVM-only; iOS se suma con mDNS + servidor nativo |
| Servidor HTTP | Ktor server (Netty JVM / CIO Android) | `createServerEngine()` expect/actual |
| Cliente | Ktor client (OkHttp Android / CIO JVM) | `HttpClient` compartido, `INFINITE_TIMEOUT` para archivos grandes |
| Descubrimiento | UDP broadcast | simple y multiplataforma (JVM+Android) |
| Persistencia | Room KMP | Android real; Desktop usa `NoopTransferDao` in-memory (TODO: driver nativo) |
| DI | Koin 4 | `appModule` común + `platformModule` por target |
| Navegación | Navigation 3 (`org.jetbrains.androidx.navigation3`) | usar la variante de JetBrains CMP; la `androidx` no funciona en Desktop |
| File picker | FileKit 0.15 | picker multiplataforma; el drag & drop se implementa con `Modifier.dragAndDropTarget` (Desktop) |

## Puerto / constantes

- `TransferDefaults.PORT` = 48213 (HTTP) — `data/network` y `domain/model/TransferDefaults.kt`.
- Descubrimiento UDP = 48432 — `data/network/UdpDeviceDiscovery.kt`.
- Rutas HTTP: `GET /` (health), `POST /upload` (multipart con `fileName` en query).