package com.apexstore.pagos;

import com.apexstore.comun.Log;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

import java.util.List;

public final class ServidorPagos {

    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args, "config/pagos.cfg")) {
            Properties props = communicator.getProperties();
            int timeoutMs = props.getPropertyAsIntWithDefault("Notificacion.TimeoutMs", 3000);
            int reintentos = props.getPropertyAsIntWithDefault("Notificacion.Reintentos", 3);
            EstrategiaBase.Notificacion notificacion = new EstrategiaBase.Notificacion(timeoutMs, reintentos);

            List<EstrategiaBase> estrategias = List.of(
                    new EstrategiaStripe(notificacion),
                    new EstrategiaPSE(notificacion),
                    new EstrategiaCripto(notificacion),
                    new EstrategiaBilleteraDigital(notificacion));

            ObjectAdapter adapter = communicator.createObjectAdapter("PagosAdapter");
            for (EstrategiaBase estrategia : estrategias) {
                adapter.add(estrategia, Util.stringToIdentity("estrategia-" + estrategia.nombre()));
                Log.info("Pagos", "Estrategia registrada: estrategia-%s", estrategia.nombre());
            }
            adapter.activate();

            Runtime.getRuntime().addShutdownHook(new Thread(communicator::shutdown));
            Log.info("Pagos", "Servidor de pasarelas (SIMULADAS) listo en %s",
                    props.getProperty("PagosAdapter.Endpoints"));
            communicator.waitForShutdown();
            estrategias.forEach(EstrategiaBase::detener);
        }
    }
}
