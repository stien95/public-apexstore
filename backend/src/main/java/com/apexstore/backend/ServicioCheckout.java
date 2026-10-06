package com.apexstore.backend;

import com.apexstore.comun.Log;
import com.apexstore.contratos.CompraInvalida;
import com.apexstore.contratos.EstadoOrden;
import com.apexstore.contratos.IGestionCompras;
import com.apexstore.contratos.IPersistenciaTransaccionalPrx;
import com.apexstore.contratos.ItemOrden;
import com.apexstore.contratos.MetodoNoDisponible;
import com.apexstore.contratos.ObservadorOrdenPrx;
import com.apexstore.contratos.ResumenOrden;
import com.apexstore.contratos.ServicioNoDisponible;
import com.apexstore.contratos.SolicitudCompra;
import com.apexstore.contratos.TransaccionPago;
import com.zeroc.Ice.Current;

import java.util.Set;
import java.util.UUID;

public final class ServicioCheckout implements IGestionCompras {

    private static final String LOG = "Checkout";
    private static final Set<String> MONEDAS = Set.of("COP", "USD");

    private final IPersistenciaTransaccionalPrx persistencia;
    private final IProcesadorPagos procesador;
    private final NotificadorClientes notificador;

    public ServicioCheckout(IPersistenciaTransaccionalPrx persistencia, IProcesadorPagos procesador,
                            NotificadorClientes notificador) {
        this.persistencia = persistencia;
        this.procesador = procesador;
        this.notificador = notificador;
    }

    @Override
    public ResumenOrden crearCompra(SolicitudCompra solicitud, ObservadorOrdenPrx observador, Current current)
            throws CompraInvalida, MetodoNoDisponible, ServicioNoDisponible {
        long total = validar(solicitud);
        String metodo = solicitud.metodoPago.toLowerCase();
        if (!procesador.soporta(metodo)) {
            throw new MetodoNoDisponible(solicitud.metodoPago, procesador.metodosDisponibles());
        }

        String moneda = solicitud.moneda.toUpperCase();
        String ordenId = UUID.randomUUID().toString();
        ResumenOrden orden = new ResumenOrden(ordenId, EstadoOrden.PendientePago, total, moneda, metodo,
                "Pago en proceso");

        try {
            persistencia.registrarOrden(orden, solicitud.clienteId, solicitud.items);
        } catch (com.zeroc.Ice.LocalException e) {
            Log.error(LOG, "No se pudo registrar la orden: %s", e.getClass().getSimpleName());
            throw new ServicioNoDisponible("No fue posible registrar la orden; intente de nuevo en unos segundos");
        }
        notificador.registrar(ordenId, observador);
        procesador.iniciarPago(new TransaccionPago(ordenId, ordenId, total, moneda, solicitud.datos), metodo);

        Log.info(LOG, "Orden %s creada (%s, %d %s): PENDIENTE_PAGO", ordenId, metodo, total, moneda);
        return orden;
    }

    @Override
    public ResumenOrden consultarOrden(String ordenId, Current current) throws CompraInvalida {
        return persistencia.obtenerOrden(ordenId);
    }

    private static long validar(SolicitudCompra s) throws CompraInvalida {
        if (s == null || s.clienteId == null || s.clienteId.isBlank()) {
            throw new CompraInvalida("El cliente es obligatorio");
        }
        if (s.items == null || s.items.length == 0) {
            throw new CompraInvalida("La compra debe tener al menos un ítem");
        }
        if (s.moneda == null || !MONEDAS.contains(s.moneda.toUpperCase())) {
            throw new CompraInvalida("Moneda no soportada: " + s.moneda + " (use COP o USD)");
        }
        if (s.metodoPago == null || s.metodoPago.isBlank()) {
            throw new CompraInvalida("El método de pago es obligatorio");
        }
        long total = 0;
        for (ItemOrden item : s.items) {
            if (item.sku == null || item.sku.isBlank() || item.cantidad <= 0 || item.precioUnitarioCentavos <= 0) {
                throw new CompraInvalida("Ítem inválido: " + item.sku);
            }
            total = Math.addExact(total, Math.multiplyExact(item.precioUnitarioCentavos, (long) item.cantidad));
        }
        return total;
    }
}
