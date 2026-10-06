package com.apexstore.backend;

import com.apexstore.comun.Log;
import com.apexstore.contratos.IPersistenciaTransaccionalPrx;
import com.apexstore.contratos.ObservadorOrdenPrx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NotificadorClientes {

    private static final String LOG = "Notificador";

    private final Map<String, ObservadorOrdenPrx> observadores = new ConcurrentHashMap<>();
    private final IPersistenciaTransaccionalPrx persistencia;
    private final int timeoutMs;

    public NotificadorClientes(IPersistenciaTransaccionalPrx persistencia, int timeoutMs) {
        this.persistencia = persistencia;
        this.timeoutMs = timeoutMs;
    }

    public void registrar(String ordenId, ObservadorOrdenPrx observador) {
        if (observador != null) {
            observadores.put(ordenId, observador.ice_invocationTimeout(timeoutMs));
        }
    }

    public void notificar(String ordenId) {
        ObservadorOrdenPrx observador = observadores.remove(ordenId);
        if (observador == null) {
            return;
        }
        persistencia.obtenerOrdenAsync(ordenId)
                .thenCompose(observador::ordenActualizadaAsync)
                .whenComplete((ok, error) -> {
                    if (error != null) {
                        Log.error(LOG, "No se pudo notificar al cliente la orden %s: %s",
                                ordenId, error.getClass().getSimpleName());
                    }
                });
    }
}
