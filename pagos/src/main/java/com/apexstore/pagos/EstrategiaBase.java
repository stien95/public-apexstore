package com.apexstore.pagos;

import com.apexstore.comun.Log;
import com.apexstore.contratos.IEstrategiaPago;
import com.apexstore.contratos.INotificacionPagoPrx;
import com.apexstore.contratos.PagoInvalido;
import com.apexstore.contratos.ResultadoPago;
import com.apexstore.contratos.TransaccionPago;
import com.zeroc.Ice.Current;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public abstract class EstrategiaBase implements IEstrategiaPago {

    public static final class Notificacion {
        final int timeoutMs;
        final int reintentos;

        public Notificacion(int timeoutMs, int reintentos) {
            this.timeoutMs = timeoutMs;
            this.reintentos = reintentos;
        }
    }

    protected static final class Cobro {
        final boolean aprobado;
        final String motivo;

        private Cobro(boolean aprobado, String motivo) {
            this.aprobado = aprobado;
            this.motivo = motivo;
        }

        static Cobro aprobado(String detalle) {
            return new Cobro(true, detalle);
        }

        static Cobro rechazado(String motivo) {
            return new Cobro(false, motivo);
        }
    }

    protected static final long COP_POR_USD = 4_000;
    protected static final long COP_POR_BTC = 400_000_000L;

    private final String nombre;
    private final Notificacion notificacion;
    private final ScheduledExecutorService ejecutor;
    private final Map<String, String> procesadas = new ConcurrentHashMap<>();

    protected EstrategiaBase(String nombre, int hilos, Notificacion notificacion) {
        this.nombre = nombre;
        this.notificacion = notificacion;
        this.ejecutor = Executors.newScheduledThreadPool(hilos, r -> {
            Thread t = new Thread(r, "pasarela-" + nombre);
            t.setDaemon(true);
            return t;
        });
    }

    public String nombre() {
        return nombre;
    }

    @Override
    public String aceptarPago(TransaccionPago tx, INotificacionPagoPrx notificador, Current current)
            throws PagoInvalido {
        Map<String, String> datos = tx.datos == null ? Map.of() : tx.datos;
        if (notificador == null) {
            throw new PagoInvalido("Se requiere un INotificacionPago para enviar el resultado");
        }
        if (tx.montoCentavos <= 0) {
            throw new PagoInvalido("El monto debe ser positivo");
        }
        validar(datos);

        String referencia = "SIM-" + nombre.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String previa = procesadas.putIfAbsent(tx.claveIdempotencia, referencia);
        if (previa != null) {
            Log.info(log(), "Reintento de la orden %s: se devuelve la referencia %s sin cobrar de nuevo",
                    tx.ordenId, previa);
            return previa;
        }

        long latencia = latenciaMs(datos);
        Log.info(log(), "Cobro aceptado orden=%s monto=%s ref=%s (respuesta simulada en %d ms)",
                tx.ordenId, describirMonto(tx), referencia, latencia);

        INotificacionPagoPrx destino = notificador.ice_invocationTimeout(notificacion.timeoutMs);
        ejecutor.schedule(() -> {
            Cobro cobro = simularCobro(datos);
            ResultadoPago resultado = new ResultadoPago(tx.ordenId, nombre, cobro.aprobado, referencia, cobro.motivo);
            for (int i = 0; i < confirmacionesEnviadas(); i++) {
                enviar(destino, resultado);
            }
        }, latencia, TimeUnit.MILLISECONDS);
        return referencia;
    }

    protected abstract void validar(Map<String, String> datos) throws PagoInvalido;

    protected abstract long latenciaMs(Map<String, String> datos);

    protected abstract Cobro simularCobro(Map<String, String> datos);

    protected abstract String describirMonto(TransaccionPago tx);

    protected int confirmacionesEnviadas() {
        return 1;
    }

    void detener() {
        ejecutor.shutdownNow();
    }

    private void enviar(INotificacionPagoPrx destino, ResultadoPago resultado) {
        for (int intento = 1; intento <= notificacion.reintentos; intento++) {
            try {
                destino.notificarResultado(resultado);
                Log.info(log(), "Resultado enviado orden=%s aprobado=%s", resultado.ordenId, resultado.aprobado);
                return;
            } catch (com.zeroc.Ice.LocalException e) {
                Log.error(log(), "Intento %d/%d de notificar la orden %s falló: %s",
                        intento, notificacion.reintentos, resultado.ordenId, e.getClass().getSimpleName());
                dormir(500L * intento);
            }
        }
        Log.error(log(), "No se pudo notificar la orden %s; queda registrada en la pasarela con ref %s",
                resultado.ordenId, resultado.referencia);
    }

    protected static String requerido(Map<String, String> datos, String clave) throws PagoInvalido {
        String valor = datos.get(clave);
        if (valor == null || valor.isBlank()) {
            throw new PagoInvalido("Falta el dato '" + clave + "'");
        }
        return valor;
    }

    protected static long aleatorio(long minimo, long maximo) {
        return ThreadLocalRandom.current().nextLong(minimo, maximo + 1);
    }

    protected static double aPesos(TransaccionPago tx) {
        double unidades = tx.montoCentavos / 100.0;
        return "USD".equalsIgnoreCase(tx.moneda) ? unidades * COP_POR_USD : unidades;
    }

    private String log() {
        return "Pasarela-" + nombre;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
