package com.apexstore.pagos;

import com.apexstore.contratos.PagoInvalido;
import com.apexstore.contratos.TransaccionPago;

import java.util.Map;
import java.util.Set;

public final class EstrategiaPSE extends EstrategiaBase {

    private static final String BANCO_CAIDO = "9999";
    private static final Set<String> TIPOS_DOC = Set.of("CC", "CE", "NIT", "PP");

    public EstrategiaPSE(Notificacion notificacion) {
        super("pse", 4, notificacion);
    }

    @Override
    protected void validar(Map<String, String> datos) throws PagoInvalido {
        String banco = requerido(datos, "codigoBanco");
        String tipoDoc = requerido(datos, "tipoDoc");
        String cuenta = requerido(datos, "numCuenta");
        if (!banco.matches("\\d{4}")) {
            throw new PagoInvalido("El código de banco debe tener 4 dígitos");
        }
        if (!TIPOS_DOC.contains(tipoDoc)) {
            throw new PagoInvalido("Tipo de documento no soportado: " + tipoDoc);
        }
        if (!cuenta.matches("\\d{6,20}")) {
            throw new PagoInvalido("Número de cuenta inválido");
        }
    }

    @Override
    protected long latenciaMs(Map<String, String> datos) {
        return BANCO_CAIDO.equals(datos.get("codigoBanco")) ? 10_000 : aleatorio(3000, 6000);
    }

    @Override
    protected Cobro simularCobro(Map<String, String> datos) {
        if (BANCO_CAIDO.equals(datos.get("codigoBanco"))) {
            return Cobro.rechazado("El banco no respondió a tiempo (timeout simulado)");
        }
        return Cobro.aprobado("Débito aprobado");
    }

    @Override
    protected String describirMonto(TransaccionPago tx) {
        return String.format("%,.0f COP", aPesos(tx));
    }
}
