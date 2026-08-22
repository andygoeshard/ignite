# Auditoría de UI — Ignite

Auditoría contra estándares Compose Multiplatform / MVI (compose-skill). Evaluación de: arquitectura, estado, recomposición, accesibilidad, UX y tema.

**Alcance ampliado (v2)**: profundiza en la UX y arquitectura de estado del flujo principal — descubrimiento → selección → conexión → negociación → transferencia → cancelación/error → finalización/recuperación. Los ítems nuevos continúan la numeración (21+); los originales no se modificaron salvo renombres de sección para alinear prioridades.

## Qué está bien (mantener)

| Práctica | Dónde |
|---|---|
| MVI respetado: `onEvent()` único, state inmutable, sin lógica en composables | `HomeViewModel`, `MviViewModel` |
| Entry → Route → Screen: Entry obtiene VM y bindea Scaffold; Screen es stateless | `HomeEntry.kt`, `HistoryEntry.kt` |
| `collectAsStateWithLifecycle()` en ambos entries | ✅ |
| Props angostos hacia hojas (`Header(deviceName...)`, no `state = state`) | `HomeScreen.kt` |
| `key = { it.id }` en LazyColumns | RadarCard, HistoryScreen |
| Estado efímero local donde corresponde (`remember(initialName)` en ProfileDialog) | ✅ |
| `canSend` derivado del estado, no duplicado | `HomeContract.kt:89` |
| Empty states con texto útil ("Buscando dispositivos…", tip Wi-Fi) | ✅ |
| Responsive con breakpoint 720dp (2 columnas en desktop) | `HomeScreen.kt:84` |

## Problemas encontrados

### P0 — Integridad de la transferencia

1. **Texto incorrecto en progreso**: `ProgressCard` dice *"Enviando a… $fileName"* — mezcla destino con archivo, y no muestra el nombre del receptor. (`HomeScreen.kt:543`)
2. **Matemática de bytes mal en multi-archivo**: `totalBytes = pendingFiles.sumOf { size }` pero `progress` es **por archivo** → muestra "50MB de 300MB" al 100% del primero. (`HomeScreen.kt:187`)
3. **Borrar historial sin confirmación**: acción destructiva directa con un tap. (`HistoryScreen.kt:47`)
4. **Conexión manual no verifica nada**: agrega el dispositivo sin probar que exista; el usuario se entera recién al fallar el envío. (`HomeViewModel.kt` connectManual)
5. **Progreso DB por chunk**: se escribía a Room por cada chunk de 64KB (~2000 writes para 126MB). *Ya corregido hoy* con throttle del 5%.
21. **No existe forma de cancelar una transferencia**: iniciado el envío, no hay botón ni gesto de cancelación — sólo matar la app. El receptor puede rechazar *antes* de empezar pero tampoco puede cortar una recepción en curso. (`HomeScreen.kt` ProgressCard, `KtorFileReceiver`)
22. **Política de concurrencia implícita**: hoy enviar y recibir corren en paralelo (el guard `sendJob != null` serializa sólo los envíos; la recepción llega independiente por eventos del receiver) y ambos compiten por las mismas ranuras visuales — `ProgressCard` + `IncomingDialog` simultáneos son posibles. Falta decisión explícita de producto: **(A)** sesión única activa bloqueando/rechazando la otra con mensaje claro, o **(B)** sesiones concurrentes modeladas independientemente con UI separada por transferencia. Además, el control actual es un flag suelto del ViewModel, no un modelo de sesión alineado con la UI.
23. **Cancelación sin contrato de limpieza**: cuando se agregue cancelar, hay que garantizar cierre de `ByteWriteChannel`/socket en el cliente, que el receptor lo detecte como fin prematuro (ya loguea `STREAM CORTADO`) conservando el parcial para reanudar, y que `CancellationException` no se registre como error genérico ni dispare reintentos.

### P1 — Arquitectura / estado

6. **Sistema de Effects muerto**: `MviViewModel` tiene Channel de Effects pero `HomeEffect`/`HistoryEffect` están vacíos y nadie los colecta. Las notificaciones van como `note: String?` en state con timeout — funciona, pero: una nota pisa a la anterior (eventos simultáneos pierden información) y errores conviven con notas transitorias en el mismo slot.
7. **Doble presentación de recepción**: aprobación inline (`ApprovalCard`) vs. recepción modal (`IncomingDialog`). Dos patrones para lo mismo; además el diálogo modal bloquea mientras transfiere.
8. **Evento muerto**: `OnStart` no hace nada (`when -> Unit`), pero `HomeEntry` lo dispara en `LaunchedEffect`.
9. **Formato de tamaño duplicado**: `formatSize()` copiado en `HomeEntry.kt:273` e `HistoryScreen.kt:133`.
24. **Máquina de estados de transferencia insuficiente**: la UI infiere la fase con booleanos sueltos (`isSending`, `activeFileName != null` en `HomeScreen.kt:183`, `isScanning`). El flujo real descubrimiento→selección→conexión→negociación→transferencia→fin no está modelado: no se distingue *Preparando/Conectando/Esperando aprobación* de *Enviando*, ni *Cancelada* de *Fallida*. Proponer una **sealed interface** de presentación — Idle, Preparing, Connecting, WaitingApproval, Sending, Receiving, Cancelling, Completed, Failed, Interrupted — con mensaje explícito por estado, en lugar de acumular booleans. Feedback esperado por estado: "Preparando archivos…", "Conectando con PC de Juan…", "Esperando aprobación…", "Enviando archivo 2 de 5…", "Cancelando transferencia…", "Se perdió la conexión", "Transferencia completada". La UI no debe reutilizar el mismo visual para situaciones distintas.
25. **Falta progreso en dos niveles**: para multi-archivo sólo existe la fracción del archivo actual; falta el nivel global. Ejemplo objetivo: *"Enviando 3 de 8 archivos"* / archivo actual "420 MB de 550 MB · 76%" / total "1,8 GB de 3,1 GB · 58%". Ambos valores deben derivar de métricas correctas sin mezclar fracción por archivo con tamaño total (extiende #2).
26. **Errores genéricos sin taxonomía**: los mensajes llegan como strings crudos del sender/receiver ("PIN incorrecto…", "Transfer failed: 500…", "Stream cortado") y la UI los muestra tal cual o los agrupa bajo un único "No se pudo enviar". Proponer clasificación de dominio — DeviceUnavailable, ConnectionTimeout, ConnectionLost, ReceiverRejected, InsufficientStorage, FileNotFound, PermissionDenied, NetworkUnavailable, Unknown — mapeada desde los errores existentes, y traducirla a texto accionable con contexto: *"Se perdió la conexión con PC de Juan. Se completaron 3 de 5 archivos."*, *"No hay espacio suficiente: se necesitan 1,8 GB adicionales."*
27. **Sin noción de "interrumpida" ni recuperación**: cierre de app, muerte del proceso, pérdida temporal de red, suspensión o reinicio dejan registros FAILED genéricos (en Android directamente nada persiste — ver deuda Room). Modelar **Interrupted separado de Failed** y al volver a la app mostrar qué pasó: *"Transferencia interrumpida — se recibieron 847 MB de 1,2 GB"*, con acciones según capacidad real: Reanudar (el protocolo ya soporta offset), Reintentar, Descartar, Ver detalles.
28. **Effects vs State sin frontera clara** (profundiza #6): clasificar explícitamente. STATE = información persistente de la pantalla: transferencia activa, progreso, solicitud entrante pendiente, error que requiere decisión del usuario. EFFECT = one-shot: snackbar, abrir file picker, solicitar permiso, navegación puntual, feedback háptico/sonoro. No convertir todo en snackbar: un error con acciones (ej. interrupción con Reanudar/Descartar) es STATE, no efecto desechable. Eliminar el patrón `note: String?` como único canal global porque pierde eventos simultáneos.

### P2 — Accesibilidad

10. **Touch target chico**: botón ✕ de la cola de archivos mide 28dp (< 48dp mínimo). (`HomeScreen.kt:456`)
11. **Sin semántica de progreso**: los `LinearProgressIndicator` no exponen % a screen readers.
12. **Sin semántica de selección**: `DeviceRow` usa `clickable`; debería usar `selectable(selected=)` para que TalkBack/VoiceOver anuncien "seleccionado".
13. **Radar decorativo sin semántica**: el Canvas del radar no anuncia cuántos dispositivos hay.
14. **Contraste dudoso**: hint de IP en `tertiary` + `labelSmall` sobre fondo claro.
29. **Radar como fuente única + precisión engañosa** (extiende #13): la lista funcional ya existe, pero el radar posiciona los blips equidistantes de forma arbitraria — esa geometría sugiere distancia/señal que el protocolo no puede garantizar. El radar debe ser complementario: contentDescription útil ("3 dispositivos encontrados"), nunca reemplazar a la lista, y la lista es la que debe mostrar estado por dispositivo ("PC-JUAN — Disponible", "Galaxy S24 — Ocupado").
30. **Accesibilidad de movimiento**: las animaciones planeadas (AnimatedContent, rotación del Refresh, pulso del radar) requieren revisión: que ningún estado importante dependa exclusivamente de animación, respetar una preferencia de reducción de movimiento (escala de animaciones del sistema en Android; settings en desktop), y limitar animaciones simultáneas para no generar ruido visual.
31. **Háptica/sonido sólo de alta intención**: vibrar por cada dispositivo que aparece en el radar sería ruido (entornos con dispositivos fluctuando). Reservar para eventos de intención clara: dispositivo seleccionado, conexión establecida, solicitud entrante, transferencia completada, error importante. Sonido sutil y opcional/configurable; en desktop degradar a no-op sin romper la experiencia (candidato a expect/actual).

### P3 — Visual / pulido

15. **Color hardcodeado**: verde `Color(0xFF2E7D32)` en éxito fuera del color scheme — rompe en dark theme. (`HomeScreen.kt:657`)
16. **letterSpacing hack**: `TextUnit(8f, Sp)` manual en PIN; usar parámetro `letterSpacing`. (`HomeScreen.kt:583`)
17. **Tema incompleto**: faltan definir `onError`, `surfaceVariant`, `outlineVariant`, etc. — usa defaults de M3 que pueden pelear con la paleta naranja custom. (`Theme.kt`)
18. **Strings hardcodeados**: todo en español inline; sin módulo de recursos (`Res.string`) — bloquea i18n futuro.
19. **Tiempo relativo estático**: `formatRelativeTime` calcula al componer y nunca refresca ("recién" por siempre hasta recomposición).
20. **Indentación rota** en bloques de `HomeScreen` (líneas 93–98, 173–217) — cosmético pero confunde lecturas.
32. **Loading/Empty/Error sin contrato tri-state**: `RadarCard` casi lo hace ("Buscando dispositivos…" vs "No hay dispositivos"), pero el error de discovery (`catch { error = "Discovery failed" }` en HomeViewModel) no se muestra en la tarjeta ni ofrece Reintentar. Formalizar los tres estados diferenciados — LOADING "Buscando dispositivos…", EMPTY "No encontramos dispositivos. Verificá que estén en la misma red Wi-Fi.", ERROR "No se pudo iniciar la búsqueda" + botón Reintentar — y nunca mostrar empty mientras carga. Evaluar skeletons/placeholders para la carga inicial.
33. **Tiempo relativo sin ticker compartido**: además del refresco estático (#19), el recálculo no debe crear un timer por fila. Usar una referencia temporal compartida por pantalla (ticker de presentación ~60s vía `produceState`) y que cada item recalcle su texto relativo a partir de ese valor común.

## Plan de mejoras priorizado

Prioridad general: integridad primero, luego máquina de estados/UX de transferencia, accesibilidad, pulido y plataforma.

### Fase 1 — P0 · Integridad de la transferencia
- [x] #22: decidir y documentar la política de concurrencia (recomendación v1: sesión única activa con mensaje claro; sesiones concurrentes recién si hay demanda real). Alinear el guard con el modelo de UI, no dejarlo como flag suelto del ViewModel. *(v1 sesión única, documentada en architecture.md; guard alineado a `SendSession`)*
- [x] #21/#23: cancelar desde la UI → estado `Cancelling` inmediato, guardas anti doble-tap, deshabilitar acciones incompatibles, cierre garantizado de streams/sockets/canales, destino del archivo parcial informado. Confirmación previa sólo para transferencias costosas. Diferenciar visualmente "Cancelando…" de "Cancelada". No tratarla como error.
- [x] Fix #1/#2: pasar `activeFile` y `targetName` reales a ProgressCard; calcular bytes por archivo actual (base para #25).
- [x] Fix #3: `AlertDialog` de confirmación antes de borrar historial.
- [x] Fix #4: al conectar manual, hacer GET `/beacon` primero; si responde, agregar con nombre real del beacon; si no, mostrar error.
- [x] Fix #5: throttle de persistencia de progreso.

### Fase 2 — P1 · Máquina de estados y UX de transferencia
- [x] #24: modelar sealed interface de estados de transferencia (Idle, Preparing, Connecting, Negotiating/WaitingApproval, Sending, Receiving, Cancelling, Completed, Failed, Interrupted) reemplazando booleanos sueltos; un mensaje de UI por estado, sin reutilizar visuales. *(hecho para el envío: `SendSession` Idle/Preparing/Sending/Cancelling; recepción sigue con su propio modelo de eventos)*
- [x] #25: progreso en dos niveles — "Enviando 3 de 8 archivos" + bytes/% del archivo actual + bytes/% globales.
- [x] #26: taxonomía de errores de dominio mapeada desde los mensajes actuales del sender/receiver; textos accionables con peer y archivos completados; distinguir Failed / Cancelled / Interrupted / Completed — nunca agrupar todo bajo "Error". *(lado emisor: `TransferError` + mensajes accionables en note y outcome; textos del receptor quedan crudos)*
- [x] #27: pantalla/recuperación de interrupciones al relanzar ("Transferencia interrumpida — se recibieron 847 MB de 1,2 GB") con Reanudar/Reintentar/Descartar/Ver detalles según soporte real del protocolo. *(v1 honesta: sweep al arrancar marca INTERRUPTED + banner con Descartar / Ver historial; reanudar real requiere persistir la ruta de origen)*
- [x] #28/#6: separar STATE vs EFFECT. Effects reales (`ShowSnackbar(message, action?)`, abrir picker, permisos, navegación puntual, háptica) sin convertir todo en snackbar; errores-con-decisión quedan en state. Eliminar `note: String?` como único canal global. *(Home e History usan `ShowSnackbar` + SnackbarHost; `note` eliminado de ambos estados)*
- [x] #7: unificar aprobación/recepción en un solo patrón (aprobación explícita + progreso no modal). *(`IncomingUi` sealed + `IncomingCard` con fase Aprobación/Recibiendo en ambas plataformas; el modal AlertDialog de recepción se eliminó; `requiresApproval` ya era true en ambas)*
- [x] Eliminar `OnStart` o darle propósito real.
- [x] Extraer `formatSize`/`formatRelativeTime` a `presentation/format/Formatters.kt`.

### Fase 3 — P2 · Accesibilidad
- [x] Touch targets ≥ 48dp en toda la app. *(M3 ya aplica MinimumInteractive en Icon/Button/TextButton; filas custom son tarjetas full-width altas)*
- [x] `Modifier.semantics { progressBarRangeInfo }` en indicadores de progreso. *(verificado: los indicadores determinados de M3 ya publican `progressBarRangeInfo` internamente; no duplicar semántica)*
- [x] `selectable(selected=)` en DeviceRow.
- [x] #29: radar complementario — contentDescription con conteo, lista como fuente funcional con estado por dispositivo, sin sugerir distancia real en la geometría. *(radar decorativo con contentDescription; la lista es la fuente funcional)*
- [x] #30: revisión de movimiento — nada crítico dependiente de animación, respetar reducción de movimiento, limitar simultáneas. *(gate `isReduceMotionEnabled()` expect/actual: Android lee `ANIMATOR_DURATION_SCALE`/`TRANSITION_ANIMATION_SCALE`, desktop false; apaga spin del Refresh, pulso del radar y slides de SessionArea — quedan fades cortos)*
- [x] #31: háptica/sonido de alta intención (selección, conexión, solicitud entrante, completado, error), opcional/configurable, no-op elegante en desktop. *(v1: `LocalHapticFeedback` en selección de dispositivo, aprobar/rechazar y resultado de envío; no-op en desktop; sin setting todavía)*
- [x] Revisar contrastes (IP hint, notas) contra WCAG AA. *(hint de IP: terciario → `onSurfaceVariant`; el resto ya usaba roles de tema estándar — banners de error usan `onErrorContainer`, snackbar usa colores por defecto de M3)*

### Fase 4 — P3 · Pulido
- [x] Completar paleta M3 (todos los slots) y reemplazar verdes hardcodeados por `colorScheme`. *(verde reemplazado; paleta completa queda pendiente)*
- [x] Animaciones: `AnimatedContent` entre estados de la máquina (#24) en vez de if/else de tarjetas, rotación del Refresh durante scan, pulso sutil en radar (todas pasan por el filtro de #30). *(SessionArea anima por fase (no por tick de progreso), Refresh gira mientras escanea, anillos del radar respiran con alpha 0.12→0.32 sólo al escanear)*
- [x] #32: contrato LOADING/EMPTY/ERROR en RadarCard e History con acción Reintentar; evaluar skeletons en carga inicial. *(RadarCard: ERROR con "Reintentar", EMPTY con "Buscar de nuevo"; History: spinner al cargar y ERROR con Reintentar vía `OnRefresh`. Skeletons descartados: las cargas son locales y rápidas, el spinner de 16dp alcanza)*
- [x] #33/#19: ticker compartido (~60s) para tiempo relativo, recalculado por fila desde la referencia común.
- [ ] Migrar strings a recursos CMP (`Res.string`) si algún día hay i18n.
- [x] letterSpacing del PIN vía parámetro de estilo (no `TextUnit` manual).
- [ ] Indentación de HomeScreen. *(las secciones reescritas quedaron bien; falta pasada completa)*
- [x] Layout expandido (>1200dp): 3 columnas (dispositivos | envío | recibidos). *(3 tiers responsivos: <720 una columna, 720–1199 dos columnas, ≥1200 tres columnas; History centrado a max 720dp)*
- [x] Desktop: Enter para enviar, foco inicial en IP field de conexión manual, atajos Ctrl+O abrir archivo. *(Enter conecta IP manual; foco inicial sólo en layouts anchos para no robar teclado en móvil; Ctrl+O global)*

### Fase 5 — Deuda técnica / plataforma
- [ ] Room Android + fix KSP: habilita historial persistente y toda la UX de recuperación (#27) en móvil.
- [ ] Background Android: ForegroundService + notificación persistente SOLO durante transferencia activa; WakeLock/Wi-Fi lock acotados a la sesión; limpieza garantizada al finalizar/cancelar; restauración de estado si el proceso es recreado.
- [ ] Notificación accionable: ver progreso, identificar archivo/dispositivo, cancelar desde la notificación (requiere puente Service↔sesión de transferencia).
- [ ] TLS: reemplazar el stub — hoy el PIN viaja en header sobre HTTP plano dentro de la LAN.

---

## Feedback de uso real (agosto 2026)

- [x] **Bug de archivos duplicados**: cada reintento del sender creaba una solicitud de aprobación nueva (transferId con timestamp) y se apilaban prompts; aceptar varias guardaba copias "(1)", "(2)". Fix: header `X-Ignite-Upload-Id` estable (hash nombre+tamaño) — los reintentos comparten la misma aprobación y extienden la ventana. Si llega OTRO archivo mientras hay una solicitud pendiente o recepción activa → 409 inmediato (`TransferError.Busy`, sin reintentos automáticos).
- [x] **Diálogo de aprobación** con Aceptar / Cancelar / Más tarde. "Más tarde" cierra el diálogo y deja un banner con cuenta atrás: la conexión queda abierta hasta decidir o vencer (**2 minutos**, ventana compartida por sender/receptor). Una solicitud nueva reemplaza a la anterior (la vieja se rechaza sola).
- [x] **Recordar PIN por dispositivo** (`TrustedDevices`): tras un envío exitoso el PIN queda persistido en `trusted_devices.json`; al seleccionar ese dispositivo se precarga solo (candadito en la fila de la lista).
- [x] **Olvidar dispositivo**: botón en cada fila confiable borra el PIN guardado — vuelve a pedir código y corta la auto-conexión.
- [x] **UI sin scrolling**: en ventanas medianas/anchas (>720dp y altura ≥460dp) no hay scroll vertical: las columnas reparten la altura con `weight` y las listas flexan dentro de su tarjeta. El radar decorativo desaparece en compacto (celular), que conserva scroll.

### Pendiente post-feedback

- [ ] Indentación completa de HomeScreen (cosmético).
- [ ] Migrar strings a recursos CMP (`Res.string`) si algún día hay i18n.
- [ ] El banner "Más tarde" sólo vive en Home: si navegás a Historial, la cuenta atrás sigue corriendo pero no se ve (v1 aceptable).

### Segunda ronda anti-duplicados (mismo día)

Revisando de nuevo el flujo quedaban dos huecos que seguían produciendo copias:

1. **Sender**: si `queryOffset` devolvía el archivo completo (`offset >= sizeBytes`), se hacía `offset = 0` y se **reenviaba todo desde cero** → el receptor lo guardaba como "(1)". Típico tras perder la respuesta OK de una transferencia que en realidad salió bien. Fix: éxito idempotente — no se reenvía nada.
2. **Receiver**: un reintento del mismo archivo después de completado volvía a pedir aprobación, y una reanudación tras corte de red también re-preguntaba. Fix: memoria `recentlyCompleted` / `recentlyApproved` con TTL de 5 min por uploadId — los reintentos tardíos reciben OK directo y las reanudaciones pasan sin re-preguntar.
3. Los pendientes huérfanos (cliente corta la conexión mientras esperabas decidir) ahora se limpian al detectar la cancelación, para no envenenar envíos futuros del mismo archivo.

### Tercera ronda — Rediseño visual cyberpunk (verde neón / negro puro)

- [x] **Tema nuevo** (`Theme.kt`): paleta verde neón `#00FF87` + cian `#00E5FF` sobre negro puro. Siempre oscuro (es la identidad del producto, no un modo). Esquinas **cortadas** (`CutCornerShape`) en cards/botones/chips → look terminal.
- [x] **Barra de progreso neón propia** (`NeonProgressBar`): track oscuro con borde, relleno degradado verde→cian y banda de brillo que recorre el tramo lleno. Reemplaza a `LinearProgressIndicator` en envío y recepción.
- [x] **Card héroe "Transmisión"** (`TransferCard`): consolida lo que estaba desparramado en 4 secciones (PIN arriba, cola, sesión, botones). Arriba el estado de sesión animado: nombre del archivo grande, barra neón 14dp, bytes/porcentaje mono, barra fina global para lotes, chip de estado (LISTO/PREPARANDO/ENVIANDO/CANCELANDO/COMPLETO/ERROR) y Cancelar en la cabecera. Debajo: cola compacta sin card propia, PIN destino mono con nota de PIN recordado, y acciones Agregar/Enviar al pie.
- [x] **Panel secundario "Dispositivos"** (`DevicesCard`): más apagado que la héroe (surfaceContainerLow). Lista tri-state compacta (#32 intacto), conexión manual inline (IP + Conectar, Enter funciona) como pie de la misma card, y el propio PIN como chip mono regenerable en la cabecera. **Radar eliminado** (decorativo, competía con lo importante).
- [x] **Jerarquía**: en compacto la card Transmisión encabeza la pantalla; dispositivos después; recibidos al final. En expandido: dispositivos | transmisión | recibidos.
- [x] Banners/cards restantes alineados: shapes del tema (cut-corner) en DeviceRow, Recibidos, Recepción entrante (barra neón), banner "Más tarde" e interrumpidas. Historial: badges con shapes del tema.
