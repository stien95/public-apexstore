package com.apexstore.comun;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class Log {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String componente, String formato, Object... args) {
        imprimir(System.out, componente, formato, args);
    }

    public static void error(String componente, String formato, Object... args) {
        imprimir(System.err, componente, formato, args);
    }

    private static void imprimir(PrintStream salida, String componente, String formato, Object... args) {
        salida.printf("%s [%s] %s%n", LocalTime.now().format(HORA), componente, String.format(formato, args));
    }
}
