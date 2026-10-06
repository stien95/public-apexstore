package com.apexstore.backend;

import com.apexstore.comun.Log;
import com.apexstore.contratos.INotificacionPago;
import com.apexstore.contratos.IPersistenciaTransaccionalPrx;
import com.apexstore.contratos.ResultadoPago;
import com.zeroc.Ice.Current;

public final class ReceptorResultadosPagos implements INotificacionPago {

    private static final String LOG = "ReceptorResultados";

    private final IPersistenciaTransaccionalPrx persistencia;
    private final NotificadorClientes notificador;

    public ReceptorResultadosPagos(IPersistenciaTransaccionalPrx persistencia, NotificadorClientes notificador) {
        this.persistencia = persistencia;
        this.notificador = notificador;
    }

    @Override
    public void notificarResultado(ResultadoPago resultado, Current current) {
        Log.info(LOG, "Callback de %s para la orden %s: %s", resultado.metodoPago, resultado.ordenId,
                resultado.aprobado ? "APROBADO" : "RECHAZADO (" + resultado.motivo + ")");
        boolean aplicado = persistencia.registrarResultadoPago(resultado);
        if (aplicado) {
            notificador.notificar(resultado.ordenId);
        } else {
            Log.info(LOG, "Resultado repetido para la orden %s: ignorado (sin doble cobro)", resultado.ordenId);
        }
    }
}
