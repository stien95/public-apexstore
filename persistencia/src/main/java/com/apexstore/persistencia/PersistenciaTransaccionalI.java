package com.apexstore.persistencia;

import com.apexstore.comun.Log;
import com.apexstore.contratos.CompraInvalida;
import com.apexstore.contratos.EstadoOrden;
import com.apexstore.contratos.IPersistenciaTransaccional;
import com.apexstore.contratos.ItemOrden;
import com.apexstore.contratos.ResultadoPago;
import com.apexstore.contratos.ResumenOrden;
import com.zeroc.Ice.Current;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class PersistenciaTransaccionalI implements IPersistenciaTransaccional {

    private static final String LOG = "Persistencia";

    private final DataSource dataSource;

    public PersistenciaTransaccionalI(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void registrarOrden(ResumenOrden orden, String clienteId, ItemOrden[] items, Current current) {
        enTransaccion(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ordenes (id, cliente_id, monto_centavos, moneda, metodo_pago, estado, detalle) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, orden.ordenId);
                ps.setString(2, clienteId);
                ps.setLong(3, orden.montoCentavos);
                ps.setString(4, orden.moneda);
                ps.setString(5, orden.metodoPago);
                ps.setString(6, aTexto(orden.estado));
                ps.setString(7, orden.detalle);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO items_orden (orden_id, sku, cantidad, precio_unitario_centavos) VALUES (?, ?, ?, ?)")) {
                for (ItemOrden item : items) {
                    ps.setString(1, orden.ordenId);
                    ps.setString(2, item.sku);
                    ps.setInt(3, item.cantidad);
                    ps.setLong(4, item.precioUnitarioCentavos);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            auditar(con, orden.ordenId, "ORDEN_CREADA",
                    "metodo=" + orden.metodoPago + " monto=" + orden.montoCentavos + " " + orden.moneda);
            return null;
        });
    }

    @Override
    public boolean registrarResultadoPago(ResultadoPago r, Current current) {
        boolean aplicado = enTransaccion(con -> {
            int insertados;
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pagos (orden_id, metodo_pago, aprobado, referencia, motivo) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (orden_id) DO NOTHING")) {
                ps.setString(1, r.ordenId);
                ps.setString(2, r.metodoPago);
                ps.setBoolean(3, r.aprobado);
                ps.setString(4, r.referencia);
                ps.setString(5, r.motivo);
                insertados = ps.executeUpdate();
            }
            if (insertados == 0) {
                auditar(con, r.ordenId, "RESULTADO_DUPLICADO", "ref=" + r.referencia);
                return false;
            }

            EstadoOrden nuevo = r.aprobado ? EstadoOrden.Pagada : EstadoOrden.Rechazada;
            int actualizadas;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE ordenes SET estado = ?, detalle = ?, actualizado = now() "
                            + "WHERE id = ? AND estado IN ('PENDIENTE_PAGO', 'FALLIDA')")) {
                ps.setString(1, aTexto(nuevo));
                ps.setString(2, r.aprobado ? "Pago aprobado, ref " + r.referencia : r.motivo);
                ps.setString(3, r.ordenId);
                actualizadas = ps.executeUpdate();
            }
            if (actualizadas == 0) {
                throw new EstadoInvalido("La orden " + r.ordenId + " no admite resultado de pago");
            }
            auditar(con, r.ordenId, r.aprobado ? "PAGO_APROBADO" : "PAGO_RECHAZADO",
                    "metodo=" + r.metodoPago + " ref=" + r.referencia + (r.aprobado ? "" : " motivo=" + r.motivo));
            return true;
        });
        Log.info(LOG, "Resultado orden %s (%s): %s", r.ordenId, r.metodoPago,
                aplicado ? (r.aprobado ? "APROBADO" : "RECHAZADO") : "duplicado ignorado");
        return aplicado;
    }

    @Override
    public boolean marcarError(String ordenId, String motivo, Current current) {
        boolean marcada = enTransaccion(con -> {
            int actualizadas;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE ordenes SET estado = 'FALLIDA', detalle = ?, actualizado = now() "
                            + "WHERE id = ? AND estado = 'PENDIENTE_PAGO'")) {
                ps.setString(1, motivo);
                ps.setString(2, ordenId);
                actualizadas = ps.executeUpdate();
            }
            if (actualizadas > 0) {
                auditar(con, ordenId, "ORDEN_FALLIDA", motivo);
            }
            return actualizadas > 0;
        });
        if (marcada) {
            Log.info(LOG, "Orden %s marcada FALLIDA: %s", ordenId, motivo);
        }
        return marcada;
    }

    @Override
    public ResumenOrden obtenerOrden(String ordenId, Current current) throws CompraInvalida {
        ResumenOrden orden = enTransaccion(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, estado, monto_centavos, moneda, metodo_pago, detalle FROM ordenes WHERE id = ?")) {
                ps.setString(1, ordenId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new ResumenOrden(rs.getString("id"), deTexto(rs.getString("estado")),
                            rs.getLong("monto_centavos"), rs.getString("moneda"),
                            rs.getString("metodo_pago"), rs.getString("detalle"));
                }
            }
        });
        if (orden == null) {
            throw new CompraInvalida("La orden " + ordenId + " no existe");
        }
        return orden;
    }

    private interface Trabajo<T> {
        T ejecutar(Connection con) throws SQLException;
    }

    private static final class EstadoInvalido extends RuntimeException {
        EstadoInvalido(String mensaje) {
            super(mensaje);
        }
    }

    private <T> T enTransaccion(Trabajo<T> trabajo) {
        try (Connection con = dataSource.getConnection()) {
            try {
                T resultado = trabajo.ejecutar(con);
                con.commit();
                return resultado;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        } catch (EstadoInvalido e) {
            Log.error(LOG, "ROLLBACK: %s", e.getMessage());
            throw e;
        } catch (SQLException e) {
            Log.error(LOG, "ROLLBACK por error SQL: %s", e.getMessage());
            throw new IllegalStateException("Error de persistencia: " + e.getMessage(), e);
        }
    }

    private static void auditar(Connection con, String ordenId, String evento, String detalle) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO auditoria (orden_id, evento, detalle) VALUES (?, ?, ?)")) {
            ps.setString(1, ordenId);
            ps.setString(2, evento);
            ps.setString(3, detalle);
            ps.executeUpdate();
        }
    }

    private static String aTexto(EstadoOrden estado) {
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

    private static EstadoOrden deTexto(String texto) {
        switch (texto) {
            case "PENDIENTE_PAGO":
                return EstadoOrden.PendientePago;
            case "PAGADA":
                return EstadoOrden.Pagada;
            case "RECHAZADA":
                return EstadoOrden.Rechazada;
            default:
                return EstadoOrden.Fallida;
        }
    }
}
