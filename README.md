# ApexStore: Punto 4 (Java + ZeroC ICE 3.7)

Este repositorio implementa la arquitectura rediseñada en el Punto 3 de la Tarea 1 (Ingeniería de Software IV, Universidad Icesi).
Cada nodo del diagrama de despliegue es un proceso Java independiente que se comunica por ZeroC ICE.

> **Las pasarelas de pago son completamente simuladas.** Ninguna estrategia abre conexiones a Stripe, PSE, redes blockchain ni a ningún otro servicio financiero.
> Las latencias, los rechazos y las referencias (`SIM-<METODO>-xxxx`) se generan localmente.

## Mapeo del diagrama al código

| Nodo (diagrama) | Módulo | Artefacto | Componentes | Interfaz ICE provista |
|---|---|---|---|---|
| Dispositivos Clientes | `cliente` | `cliente.jar` | `ClienteConsola` (reemplaza WebApp/MobileApp) | `ObservadorOrden` (callback) |
| Servidor E-Commerce (Backend Core) | `backend` | `backend.jar` | `ServicioCheckout`, `ProcesadorPagosContexto`, `ReceptorResultadosPagos` | `IGestionCompras`, `INotificacionPago` |
| Servidor de Pasarelas y Estrategias de Pago | `pagos` | `pagos.jar` | `EstrategiaStripe`, `EstrategiaPSE`, `EstrategiaCripto`, `EstrategiaBilleteraDigital` | `IEstrategiaPago` |
| Servidor Base de Datos Transaccional | `persistencia` + Docker | `persistencia.jar` + PostgreSQL 16 | `ServidorPersistencia` → JDBC → `apexstore_tx` | `IPersistenciaTransaccional` |
| (contratos compartidos) | `contratos` | - | Código generado de [`slice/ApexStore.ice`](slice/ApexStore.ice) | - |

`IProcesadorPagos` es una interfaz Java interna del backend porque `ServicioCheckout` y `ProcesadorPagosContexto` corren en el mismo JVM.

### Cómo se corrigen los malos usos del Punto 1

| Observación | En el código |
|---|---|
| 1.2: firmas distintas por pasarela | Todas implementan la misma `IEstrategiaPago.aceptarPago(TransaccionPago, INotificacionPago*)`. Los datos propios de cada medio viajan en `DatosPago` (un diccionario opaco que solo interpreta la estrategia). |
| 1.3: Cripto escribía directo en la BD | `pagos` no tiene driver JDBC. Cripto notifica por `INotificacionPago` igual que las demás estrategias. |
| 1.4: `ProcesadorPagosContexto` sobrecargado | El contexto solo elige la estrategia y la despacha. La recepción de callbacks está en `ReceptorResultadosPagos` y el acceso a datos en `ServidorPersistencia`. |
| RAS-04: nuevo medio de pago | `EstrategiaBilleteraDigital` se agregó sin tocar el código del backend: basta con la línea `Estrategias=...` en `config/backend.cfg`. |

## Flujo de una compra

1. El cliente llama a `IGestionCompras.crearCompra(solicitud, observador)`.
2. `ServicioCheckout` valida la compra, persiste la orden como `PENDIENTE_PAGO` (antes de cobrar, para que ningún pago quede huérfano) y delega en el contexto. Responde de inmediato.
3. `ProcesadorPagosContexto` invoca `aceptarPagoAsync` (AMI de ICE, no bloquea) sobre la estrategia elegida, con un *circuit breaker* por estrategia.
4. La estrategia simulada valida sus datos, retorna una referencia y, en un pool de hilos propio, simula la latencia del proveedor.
5. La estrategia llama a `INotificacionPago.notificarResultado` en `ReceptorResultadosPagos`.
6. `ServidorPersistencia` registra el resultado en **una sola transacción ACID** (`pagos` + `ordenes` + `auditoria`). La restricción `UNIQUE(orden_id)` hace que una confirmación repetida no genere doble cobro.
7. El backend le envía al cliente el estado final por `ObservadorOrden.ordenActualizada`.

## Requisitos

- JDK 17
- **ZeroC Ice 3.7.10**, que provee `slice2java`. En Windows se instala con el MSI oficial de https://zeroc.com/ice/downloads/3.7.
  El build busca `slice2java` en `-PiceHome=<ruta>`, luego en la variable `ICE_HOME`, luego en `C:\Program Files\ZeroC\Ice-3.7.10\bin` y por último en el `PATH`.
- Docker Desktop (para PostgreSQL 16)

El runtime de ICE (`com.zeroc:ice:3.7.10`) se descarga de Maven Central.

## Compilar

```bash
./gradlew build
```

Genera los jars ejecutables en `build/dist/`: `persistencia.jar`, `pagos.jar`, `backend.jar` y `cliente.jar`.

## Ejecutar

Todos los comandos se ejecutan desde la raíz del repositorio, porque cada nodo lee `config/<nodo>.cfg`. Cada nodo va en su propia terminal y en este orden:

```bash
docker compose up -d
```

```bash
java -jar build/dist/persistencia.jar
```

```bash
java -jar build/dist/pagos.jar
```

```bash
java -jar build/dist/backend.jar
```

```bash
java -jar build/dist/cliente.jar demo
```

Modos del cliente:

- `demo`: escenarios con todos los medios de pago, incluidos rechazos, banco caído, confirmación duplicada y método inexistente.
- `carga [N] [paralelo]`: N compras concurrentes (500 y 50 por defecto). Reporta P50, P95 y P99 de `crearCompra` y si cumple RAS-02 (P95 ≤ 250 ms).
- Sin argumentos: menú interactivo.

También se puede ejecutar con Gradle, por ejemplo `./gradlew :backend:run` o `./gradlew :cliente:run --args="demo"`.

Para desplegar en máquinas distintas basta con cambiar los `-h localhost` de los archivos `config/*.cfg`.

## Datos de prueba de las pasarelas simuladas

| Medio | Claves de `DatosPago` | Cómo forzar un rechazo |
|---|---|---|
| `stripe` | `token` (`tok_...`), `cvc` | `token=tok_rechazada` o `cvc=000` |
| `pse` | `codigoBanco` (4 dígitos), `tipoDoc` (CC/CE/NIT/PP), `numCuenta` | `codigoBanco=9999`: banco que no responde (timeout simulado de 10 s) |
| `cripto` | `direccionWallet`, `red` (`bitcoin`/`lightning`) | (siempre aprueba; envía la confirmación **dos veces**) |
| `billetera` | `telefono` (celular de 10 dígitos) | teléfono terminado en `0000`: sin saldo |

## Escenarios de verificación

| Escenario | Qué se observa |
|---|---|
| Respuesta inmediata | `crearCompra` responde `PENDIENTE_PAGO` en pocos ms aunque PSE tarde 3-10 s |
| Aislamiento de fallos | El banco PSE caído no retrasa las órdenes de Stripe ni de la billetera |
| Sin doble cobro | Cripto confirma dos veces y el backend registra un solo pago y una auditoría `RESULTADO_DUPLICADO` |
| Circuit breaker | Detener `pagos.jar` y comprar 3 veces: las órdenes quedan `FALLIDA` y luego el circuito se abre (fallo rápido sin red) |
| Persistencia caída | `crearCompra` responde `ServicioNoDisponible` y no se cobra nada |
| RAS-02 | `java -jar build/dist/cliente.jar carga 1000 50` |

Para revisar la base de datos:

```bash
docker exec -it apexstore-postgres psql -U apex -d apexstore_tx -c "SELECT estado, count(*) FROM ordenes GROUP BY estado;"
```

```bash
docker exec -it apexstore-postgres psql -U apex -d apexstore_tx -c "SELECT orden_id, evento, detalle FROM auditoria ORDER BY id DESC LIMIT 20;"
```

## Puertos

| Nodo | Adapter | Puerto |
|---|---|---|
| backend | `CheckoutAdapter` (`IGestionCompras`) | 10000 |
| backend | `NotificacionesAdapter` (`INotificacionPago`) | 10001 |
| pagos | `PagosAdapter` (`IEstrategiaPago` ×4) | 10010 |
| persistencia | `PersistenciaAdapter` (`IPersistenciaTransaccional`) | 10020 |
| PostgreSQL | - | 55432 (host) → 5432 (contenedor) |
