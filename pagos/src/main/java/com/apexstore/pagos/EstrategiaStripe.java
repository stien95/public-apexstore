package com.apexstore.pagos;

import com.apexstore.contratos.PagoInvalido;
import com.apexstore.contratos.TransaccionPago;

import java.util.Map;

public final class EstrategiaStripe extends EstrategiaBase {

    public EstrategiaStripe(Notificacion notificacion) {
        super("stripe", 4, notificacion);
    }

    @Override
    protected void validar(Map<String, String> datos) throws PagoInvalido {
        String token = requerido(datos, "token");
        String cvc = requerido(datos, "cvc");
        if (!token.startsWith("tok_")) {
            throw new PagoInvalido("Token de tarjeta inválido");
        }
        if (!cvc.matches("\\d{3,4}")) {
            throw new PagoInvalido("El CVC debe tener 3 o 4 dígitos");
        }
    }

    @Override
    protected long latenciaMs(Map<String, String> datos) {
        return aleatorio(500, 1500);
    }

    @Override
    protected Cobro simularCobro(Map<String, String> datos) {
        if ("tok_rechazada".equals(datos.get("token"))) {
            return Cobro.rechazado("Tarjeta declinada por el emisor (simulado)");
        }
        if ("000".equals(datos.get("cvc"))) {
            return Cobro.rechazado("CVC incorrecto (simulado)");
        }
        return Cobro.aprobado("Cargo autorizado");
    }

    @Override
    protected String describirMonto(TransaccionPago tx) {
        return String.format("%.2f USD", aPesos(tx) / COP_POR_USD);
    }
}
