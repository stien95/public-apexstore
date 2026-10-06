package com.apexstore.cliente;

import com.apexstore.comun.Log;
import com.apexstore.contratos.ObservadorOrden;
import com.apexstore.contratos.ResumenOrden;
import com.zeroc.Ice.Current;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ObservadorOrdenI implements ObservadorOrden {

    private final Map<String, CompletableFuture<ResumenOrden>> pendientes = new ConcurrentHashMap<>();
    private volatile boolean silencioso;

    @Override
    public void ordenActualizada(ResumenOrden orden, Current current) {
        if (!silencioso) {
            Log.info("Cliente", "<<< Notificación: orden %s -> %s (%s)",
                    corto(orden.ordenId), Formato.estado(orden.estado), orden.detalle);
        }
        resultado(orden.ordenId).complete(orden);
    }

    public CompletableFuture<ResumenOrden> resultado(String ordenId) {
        return pendientes.computeIfAbsent(ordenId, id -> new CompletableFuture<>());
    }

    public void setSilencioso(boolean silencioso) {
        this.silencioso = silencioso;
    }

    static String corto(String ordenId) {
        return ordenId.length() > 8 ? ordenId.substring(0, 8) : ordenId;
    }
}
