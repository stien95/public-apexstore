package com.apexstore.cliente;

import com.apexstore.comun.Log;
import com.apexstore.contratos.CompraInvalida;
import com.apexstore.contratos.IGestionComprasPrx;
import com.apexstore.contratos.ItemOrden;
import com.apexstore.contratos.MetodoNoDisponible;
import com.apexstore.contratos.ObservadorOrdenPrx;
import com.apexstore.contratos.ResumenOrden;
import com.apexstore.contratos.ServicioNoDisponible;
import com.apexstore.contratos.SolicitudCompra;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClienteConsola {

    private static final String LOG = "Cliente";

    private final IGestionComprasPrx checkout;
    private final ObservadorOrdenPrx observadorPrx;
    private final ObservadorOrdenI observador;

    private ClienteConsola(IGestionComprasPrx checkout, ObservadorOrdenPrx observadorPrx, ObservadorOrdenI observador) {
        this.checkout = checkout;
        this.observadorPrx = observadorPrx;
        this.observador = observador;
    }

    public static void main(String[] args) throws Exception {
        List<String> argumentos = new ArrayList<>();
        try (Communicator communicator = Util.initialize(args, "config/cliente.cfg", argumentos)) {
            IGestionComprasPrx checkout = IGestionComprasPrx.checkedCast(communicator.propertyToProxy("Checkout.Proxy"));
            if (checkout == null) {
                Log.error(LOG, "El proxy configurado en Checkout.Proxy no es un IGestionCompras");
                return;
            }

            ObjectAdapter adapter = communicator.createObjectAdapter("ClienteAdapter");
            ObservadorOrdenI observador = new ObservadorOrdenI();
            ObservadorOrdenPrx observadorPrx = ObservadorOrdenPrx.uncheckedCast(adapter.addWithUUID(observador));
            adapter.activate();

            ClienteConsola cliente = new ClienteConsola(checkout, observadorPrx, observador);
            String modo = argumentos.isEmpty() ? "menu" : argumentos.get(0);
            switch (modo) {
                case "demo":
                    cliente.demo();
                    break;
                case "carga":
                    int total = argumentos.size() > 1 ? Integer.parseInt(argumentos.get(1)) : 500;
                    int paralelo = argumentos.size() > 2 ? Integer.parseInt(argumentos.get(2)) : 50;
                    cliente.carga(total, paralelo);
                    break;
                default:
                    cliente.menu();
                    break;
            }
        } catch (com.zeroc.Ice.ConnectionRefusedException e) {
            Log.error(LOG, "No se pudo conectar con el backend. ¿Está corriendo backend.jar?");
        }
    }

    private void demo() throws InterruptedException {
        System.out.println("=== ApexStore: demostración de checkout con pasarelas SIMULADAS ===");
        List<ResumenOrden> creadas = new ArrayList<>();

        creadas.add(comprar("Stripe aprobada", "stripe", Formato.datosEjemplo("stripe")));
        creadas.add(comprar("Stripe declinada", "stripe", Map.of("token", "tok_rechazada", "cvc", "123")));
        creadas.add(comprar("Stripe sin CVC (datos inválidos)", "stripe", Map.of("token", "tok_visa")));
        creadas.add(comprar("PSE aprobada (3-6 s)", "pse", Formato.datosEjemplo("pse")));
        creadas.add(comprar("PSE banco caído (timeout)", "pse",
                Map.of("codigoBanco", "9999", "tipoDoc", "CC", "numCuenta", "1234567890")));
        creadas.add(comprar("Cripto (envía confirmación doble)", "cripto", Formato.datosEjemplo("cripto")));
        creadas.add(comprar("Billetera digital (medio nuevo)", "billetera", Formato.datosEjemplo("billetera")));
        creadas.add(comprar("Billetera sin saldo", "billetera", Map.of("telefono", "3009990000")));
        creadas.add(comprar("Método inexistente", "paypal", Map.of()));
        creadas.removeIf(o -> o == null);

        System.out.println();
        System.out.println("Todas las respuestas llegaron de inmediato. Esperando las notificaciones asíncronas...");
        System.out.println();
        CompletableFuture<?>[] futuros = creadas.stream()
                .map(o -> observador.resultado(o.ordenId))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futuros).get(25, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("(algunas notificaciones no llegaron a tiempo; se consulta el estado)");
        }

        System.out.println();
        System.out.println("=== Estado final (consultarOrden) ===");
        System.out.printf("%-10s %-10s %14s  %-15s %s%n", "ORDEN", "METODO", "MONTO", "ESTADO", "DETALLE");
        for (ResumenOrden o : creadas) {
            try {
                ResumenOrden actual = checkout.consultarOrden(o.ordenId);
                System.out.printf("%-10s %-10s %14s  %-15s %s%n", ObservadorOrdenI.corto(actual.ordenId),
                        actual.metodoPago, Formato.pesos(actual.montoCentavos), Formato.estado(actual.estado),
                        actual.detalle);
            } catch (CompraInvalida e) {
                System.out.println(o.ordenId + ": " + e.motivo);
            }
        }
    }

    private ResumenOrden comprar(String escenario, String metodo, Map<String, String> datos) {
        SolicitudCompra solicitud = solicitud(metodo, datos, 2, 189_900_00L);
        long inicio = System.nanoTime();
        try {
            ResumenOrden orden = checkout.crearCompra(solicitud, observadorPrx);
            observador.resultado(orden.ordenId);
            Log.info(LOG, ">>> %-34s respuesta en %3d ms: orden %s %s", escenario, msDesde(inicio),
                    ObservadorOrdenI.corto(orden.ordenId), Formato.estado(orden.estado));
            return orden;
        } catch (MetodoNoDisponible e) {
            Log.info(LOG, ">>> %-34s rechazada al instante: '%s' no disponible (opciones: %s)",
                    escenario, e.metodo, e.disponibles);
        } catch (CompraInvalida e) {
            Log.info(LOG, ">>> %-34s compra inválida: %s", escenario, e.motivo);
        } catch (ServicioNoDisponible e) {
            Log.info(LOG, ">>> %-34s servicio no disponible: %s", escenario, e.motivo);
        }
        return null;
    }

    private void carga(int total, int paralelo) throws InterruptedException {
        System.out.printf("=== Prueba de carga: %d compras, %d en paralelo (billetera digital) ===%n", total, paralelo);
        observador.setSilencioso(true);
        List<Long> latencias = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errores = new AtomicInteger();
        Semaphore ventana = new Semaphore(paralelo);
        CountDownLatch fin = new CountDownLatch(total);
        SolicitudCompra solicitud = solicitud("billetera", Formato.datosEjemplo("billetera"), 1, 50_000_00L);

        long inicioTotal = System.nanoTime();
        for (int i = 0; i < total; i++) {
            ventana.acquire();
            long inicio = System.nanoTime();
            checkout.crearCompraAsync(solicitud, null).whenComplete((orden, error) -> {
                if (error == null) {
                    latencias.add(msDesde(inicio));
                } else {
                    errores.incrementAndGet();
                }
                ventana.release();
                fin.countDown();
            });
        }
        fin.await();
        long duracionMs = msDesde(inicioTotal);

        List<Long> ordenadas = new ArrayList<>(latencias);
        Collections.sort(ordenadas);
        System.out.printf("Completadas: %d | errores: %d | duración: %d ms | throughput: %.0f compras/s%n",
                ordenadas.size(), errores.get(), duracionMs, ordenadas.size() * 1000.0 / Math.max(1, duracionMs));
        if (!ordenadas.isEmpty()) {
            long p95 = percentil(ordenadas, 95);
            System.out.printf("Latencia de crearCompra -> P50: %d ms | P95: %d ms | P99: %d ms | máx: %d ms%n",
                    percentil(ordenadas, 50), p95, percentil(ordenadas, 99), ordenadas.get(ordenadas.size() - 1));
            System.out.printf("RAS-02 (P95 <= 250 ms): %s%n", p95 <= 250 ? "CUMPLE" : "NO CUMPLE");
        }
    }

    private static long percentil(List<Long> ordenadas, int p) {
        int indice = (int) Math.ceil(p / 100.0 * ordenadas.size()) - 1;
        return ordenadas.get(Math.max(0, Math.min(indice, ordenadas.size() - 1)));
    }

    private void menu() {
        Scanner in = new Scanner(System.in);
        while (true) {
            System.out.println();
            System.out.println("1) Comprar   2) Consultar orden   3) Salir");
            System.out.print("> ");
            if (!in.hasNextLine()) {
                return;
            }
            String opcion = in.nextLine().trim();
            if (opcion.equals("1")) {
                System.out.print("Medio de pago (stripe, pse, cripto, billetera): ");
                String metodo = in.nextLine().trim().toLowerCase();
                System.out.print("Precio unitario en COP: ");
                long precio = leerNumero(in.nextLine(), 100_000);
                comprar("Compra manual", metodo, Formato.datosEjemplo(metodo), precio);
            } else if (opcion.equals("2")) {
                System.out.print("Id de la orden: ");
                String id = in.nextLine().trim();
                try {
                    ResumenOrden o = checkout.consultarOrden(id);
                    System.out.printf("%s | %s | %s | %s%n", o.ordenId, Formato.estado(o.estado),
                            Formato.pesos(o.montoCentavos), o.detalle);
                } catch (CompraInvalida e) {
                    System.out.println(e.motivo);
                }
            } else if (opcion.equals("3")) {
                return;
            }
        }
    }

    private void comprar(String escenario, String metodo, Map<String, String> datos, long precioPesos) {
        SolicitudCompra solicitud = solicitud(metodo, datos, 1, precioPesos * 100);
        long inicio = System.nanoTime();
        try {
            ResumenOrden orden = checkout.crearCompra(solicitud, observadorPrx);
            System.out.printf("Orden %s -> %s en %d ms. El resultado llegará como notificación.%n",
                    orden.ordenId, Formato.estado(orden.estado), msDesde(inicio));
        } catch (MetodoNoDisponible e) {
            System.out.println("Medio no disponible. Opciones: " + e.disponibles);
        } catch (CompraInvalida e) {
            System.out.println("Compra inválida: " + e.motivo);
        } catch (ServicioNoDisponible e) {
            System.out.println("Servicio no disponible: " + e.motivo);
        }
    }

    private static long leerNumero(String texto, long defecto) {
        try {
            return Long.parseLong(texto.trim());
        } catch (NumberFormatException e) {
            return defecto;
        }
    }

    private static SolicitudCompra solicitud(String metodo, Map<String, String> datos, int cantidad,
                                             long precioCentavos) {
        ItemOrden[] items = { new ItemOrden("SKU-AUDIFONOS-01", cantidad, precioCentavos) };
        return new SolicitudCompra("cliente-demo", items, "COP", metodo, datos);
    }

    private static long msDesde(long inicioNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
    }
}
