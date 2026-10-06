package com.apexstore.pagos;

import com.apexstore.contratos.PagoInvalido;
import com.apexstore.contratos.TransaccionPago;

import java.util.Map;
import java.util.Set;

public final class EstrategiaCripto extends EstrategiaBase {

    private static final Set<String> REDES = Set.of("bitcoin", "lightning");

    public EstrategiaCripto(Notificacion notificacion) {
        super("cripto", 2, notificacion);
    }

    @Override
    protected void validar(Map<String, String> datos) throws PagoInvalido {
        String wallet = requerido(datos, "direccionWallet");
        String red = requerido(datos, "red");
        if (!REDES.contains(red)) {
            throw new PagoInvalido("Red no soportada: " + red);
        }
        if (wallet.length() < 14) {
            throw new PagoInvalido("Dirección de wallet inválida");
        }
    }

    @Override
    protected long latenciaMs(Map<String, String> datos) {
        return "lightning".equals(datos.get("red")) ? aleatorio(1000, 2000) : aleatorio(4000, 8000);
    }

    @Override
    protected Cobro simularCobro(Map<String, String> datos) {
        return Cobro.aprobado("Transacción confirmada en la red " + datos.get("red"));
    }

    @Override
    protected String describirMonto(TransaccionPago tx) {
        long satoshis = Math.round(aPesos(tx) / COP_POR_BTC * 100_000_000L);
        return satoshis + " sats";
    }

    @Override
    protected int confirmacionesEnviadas() {
        return 2;
    }
}
