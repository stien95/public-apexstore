package com.apexstore.backend;

import com.apexstore.comun.Log;
import com.apexstore.contratos.IEstrategiaPagoPrx;
import com.apexstore.contratos.INotificacionPagoPrx;
import com.apexstore.contratos.IPersistenciaTransaccionalPrx;
import com.apexstore.contratos.PagoInvalido;
import com.apexstore.contratos.ResultadoPago;
import com.apexstore.contratos.TransaccionPago;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Properties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProcesadorPagosContexto implements IProcesadorPagos {

    private static final String LOG = "ProcesadorPagos";

    private final Map<String, IEstrategiaPagoPrx> estrategias = new LinkedHashMap<>();
    private final Map<String, CircuitBreaker> disyuntores = new LinkedHashMap<>();
    private final INotificacionPagoPrx receptor;
    private final IPersistenciaTransaccionalPrx persistencia;
    private final NotificadorClientes notificador;
    private final ExecutorService ejecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "procesador-pagos");
        t.setDaemon(true);
        return t;
    });

    public ProcesadorPagosContexto(Communicator communicator, INotificacionPagoPrx receptor,
                                   IPersistenciaTransaccionalPrx persistencia, NotificadorClientes notificador) {
        this.receptor = receptor;
        this.persistencia = persistencia;
        this.notificador = notificador;

        Properties props = communicator.getProperties();
        String endpoints = props.getProperty("Pagos.Endpoints");
        int timeoutMs = props.getPropertyAsIntWithDefault("Pagos.TimeoutMs", 1500);
        int umbral = props.getPropertyAsIntWithDefault("CircuitBreaker.UmbralFallos", 3);
        int esperaMs = props.getPropertyAsIntWithDefault("CircuitBreaker.EsperaMs", 15000);

        for (String nombre : props.getPropertyAsList("Estrategias")) {
            String metodo = nombre.trim().toLowerCase();
            if (metodo.isEmpty()) {
                continue;
            }
            IEstrategiaPagoPrx proxy = IEstrategiaPagoPrx.uncheckedCast(
                    communicator.stringToProxy("estrategia-" + metodo + ":" + endpoints))
                    .ice_invocationTimeout(timeoutMs);
            estrategias.put(metodo, proxy);
            disyuntores.put(metodo, new CircuitBreaker(metodo, umbral, esperaMs));
        }
        Log.info(LOG, "Estrategias configuradas: %s", metodosDisponibles());
    }

    @Override
    public boolean soporta(String metodoPago) {
        return metodoPago != null && estrategias.containsKey(metodoPago.toLowerCase());
    }

    @Override
    public String metodosDisponibles() {
        return String.join(", ", estrategias.keySet());
    }

    @Override
    public void iniciarPago(TransaccionPago tx, String metodoPago) {
        String metodo = metodoPago.toLowerCase();
        CircuitBreaker disyuntor = disyuntores.get(metodo);
        if (!disyuntor.permiteLlamada()) {
            fallar(tx.ordenId, "La pasarela " + metodo + " no está disponible en este momento; intente otro medio de pago");
            return;
        }

        estrategias.get(metodo).aceptarPagoAsync(tx, receptor).whenCompleteAsync((referencia, error) -> {
            Throwable causa = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (causa == null) {
                disyuntor.registrarExito();
                Log.info(LOG, "Orden %s despachada a %s (ref %s)", tx.ordenId, metodo, referencia);
            } else if (causa instanceof PagoInvalido) {
                disyuntor.registrarExito();
                rechazar(tx.ordenId, metodo, ((PagoInvalido) causa).motivo);
            } else {
                disyuntor.registrarFallo();
                fallar(tx.ordenId, "La pasarela " + metodo + " no respondió (" + causa.getClass().getSimpleName() + ")");
            }
        }, ejecutor);
    }

    private void rechazar(String ordenId, String metodo, String motivo) {
        Log.info(LOG, "Orden %s rechazada por datos inválidos: %s", ordenId, motivo);
        persistencia.registrarResultadoPagoAsync(new ResultadoPago(ordenId, metodo, false, "", motivo))
                .whenComplete((aplicado, error) -> avisar(ordenId, aplicado, error));
    }

    private void fallar(String ordenId, String motivo) {
        Log.error(LOG, "Orden %s FALLIDA: %s", ordenId, motivo);
        persistencia.marcarErrorAsync(ordenId, motivo)
                .whenComplete((marcada, error) -> avisar(ordenId, marcada, error));
    }

    private void avisar(String ordenId, Boolean cambio, Throwable error) {
        if (error != null) {
            Log.error(LOG, "No se pudo actualizar la orden %s: %s", ordenId, error.getClass().getSimpleName());
        } else if (Boolean.TRUE.equals(cambio)) {
            notificador.notificar(ordenId);
        }
    }

    void detener() {
        ejecutor.shutdownNow();
    }
}
