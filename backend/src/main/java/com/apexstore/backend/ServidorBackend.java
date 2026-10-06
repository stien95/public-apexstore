package com.apexstore.backend;

import com.apexstore.comun.Log;
import com.apexstore.contratos.INotificacionPagoPrx;
import com.apexstore.contratos.IPersistenciaTransaccionalPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

public final class ServidorBackend {

    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args, "config/backend.cfg")) {
            Properties props = communicator.getProperties();

            IPersistenciaTransaccionalPrx persistencia = IPersistenciaTransaccionalPrx
                    .uncheckedCast(communicator.propertyToProxy("Persistencia.Proxy"))
                    .ice_invocationTimeout(props.getPropertyAsIntWithDefault("Persistencia.TimeoutMs", 2000));
            NotificadorClientes notificador = new NotificadorClientes(persistencia,
                    props.getPropertyAsIntWithDefault("Observador.TimeoutMs", 2000));

            ObjectAdapter notificaciones = communicator.createObjectAdapter("NotificacionesAdapter");
            Identity idReceptor = Util.stringToIdentity("receptor-pagos");
            notificaciones.add(new ReceptorResultadosPagos(persistencia, notificador), idReceptor);
            INotificacionPagoPrx receptor = INotificacionPagoPrx.uncheckedCast(notificaciones.createProxy(idReceptor));

            ProcesadorPagosContexto procesador = new ProcesadorPagosContexto(communicator, receptor, persistencia,
                    notificador);
            ObjectAdapter checkout = communicator.createObjectAdapter("CheckoutAdapter");
            checkout.add(new ServicioCheckout(persistencia, procesador, notificador), Util.stringToIdentity("checkout"));

            notificaciones.activate();
            checkout.activate();

            Runtime.getRuntime().addShutdownHook(new Thread(communicator::shutdown));
            Log.info("Backend", "IGestionCompras en %s | INotificacionPago en %s",
                    props.getProperty("CheckoutAdapter.Endpoints"), props.getProperty("NotificacionesAdapter.Endpoints"));
            communicator.waitForShutdown();
            procesador.detener();
        }
    }
}
