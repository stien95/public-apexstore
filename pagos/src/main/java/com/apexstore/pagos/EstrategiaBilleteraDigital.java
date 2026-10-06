package com.apexstore.pagos;

import com.apexstore.contratos.PagoInvalido;
import com.apexstore.contratos.TransaccionPago;

import java.util.Map;

public final class EstrategiaBilleteraDigital extends EstrategiaBase {

    public EstrategiaBilleteraDigital(Notificacion notificacion) {
        super("billetera", 4, notificacion);
    }

    @Override
    protected void validar(Map<String, String> datos) throws PagoInvalido {
        String telefono = requerido(datos, "telefono");
        if (!telefono.matches("3\\d{9}")) {
            throw new PagoInvalido("El teléfono debe ser un celular de 10 dígitos");
        }
    }

    @Override
    protected long latenciaMs(Map<String, String> datos) {
        return aleatorio(300, 1000);
    }

    @Override
    protected Cobro simularCobro(Map<String, String> datos) {
        if (datos.get("telefono").endsWith("0000")) {
            return Cobro.rechazado("Saldo insuficiente en la billetera (simulado)");
        }
        return Cobro.aprobado("Pago aprobado desde la billetera");
    }

    @Override
    protected String describirMonto(TransaccionPago tx) {
        return String.format("%,.0f COP", aPesos(tx));
    }
}
