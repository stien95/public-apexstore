package com.apexstore.cliente;

import com.apexstore.contratos.EstadoOrden;

import java.util.LinkedHashMap;
import java.util.Map;

final class Formato {

    private Formato() {
    }

    static String estado(EstadoOrden estado) {
        switch (estado) {
            case PendientePago:
                return "PENDIENTE_PAGO";
            case Pagada:
                return "PAGADA";
            case Rechazada:
                return "RECHAZADA";
            default:
                return "FALLIDA";
        }
    }

    static String pesos(long centavos) {
        return String.format("$%,.2f", centavos / 100.0);
    }

    static Map<String, String> datosEjemplo(String metodo) {
        Map<String, String> datos = new LinkedHashMap<>();
        switch (metodo) {
            case "stripe":
                datos.put("token", "tok_visa_simulada");
                datos.put("cvc", "123");
                break;
            case "pse":
                datos.put("codigoBanco", "1007");
                datos.put("tipoDoc", "CC");
                datos.put("numCuenta", "1234567890");
                break;
            case "cripto":
                datos.put("direccionWallet", "bc1qsimulada000apexstore");
                datos.put("red", "bitcoin");
                break;
            case "billetera":
                datos.put("telefono", "3001234567");
                break;
            default:
                break;
        }
        return datos;
    }
}
