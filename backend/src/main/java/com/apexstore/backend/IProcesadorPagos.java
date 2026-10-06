package com.apexstore.backend;

import com.apexstore.contratos.TransaccionPago;

public interface IProcesadorPagos {

    boolean soporta(String metodoPago);

    String metodosDisponibles();

    void iniciarPago(TransaccionPago transaccion, String metodoPago);
}
