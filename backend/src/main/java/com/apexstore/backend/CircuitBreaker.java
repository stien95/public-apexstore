package com.apexstore.backend;

import com.apexstore.comun.Log;

final class CircuitBreaker {

    private final String nombre;
    private final int umbralFallos;
    private final long esperaMs;

    private int fallosConsecutivos;
    private long abiertoHasta;

    CircuitBreaker(String nombre, int umbralFallos, long esperaMs) {
        this.nombre = nombre;
        this.umbralFallos = umbralFallos;
        this.esperaMs = esperaMs;
    }

    synchronized boolean permiteLlamada() {
        return System.currentTimeMillis() >= abiertoHasta;
    }

    synchronized void registrarExito() {
        if (fallosConsecutivos >= umbralFallos) {
            Log.info("CircuitBreaker", "%s: CERRADO (la pasarela se recuperó)", nombre);
        }
        fallosConsecutivos = 0;
        abiertoHasta = 0;
    }

    synchronized void registrarFallo() {
        fallosConsecutivos++;
        if (fallosConsecutivos >= umbralFallos) {
            abiertoHasta = System.currentTimeMillis() + esperaMs;
            Log.error("CircuitBreaker", "%s: ABIERTO por %d ms tras %d fallos consecutivos",
                    nombre, esperaMs, fallosConsecutivos);
        }
    }
}
