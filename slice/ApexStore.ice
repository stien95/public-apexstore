[["java:package:com.apexstore"]]
module contratos
{
    dictionary<string, string> DatosPago;

    struct ItemOrden
    {
        string sku;
        int cantidad;
        long precioUnitarioCentavos;
    }
    sequence<ItemOrden> Items;

    enum EstadoOrden { PendientePago, Pagada, Rechazada, Fallida }

    struct SolicitudCompra
    {
        string clienteId;
        Items items;
        string moneda;
        string metodoPago;
        DatosPago datos;
    }

    struct ResumenOrden
    {
        string ordenId;
        EstadoOrden estado;
        long montoCentavos;
        string moneda;
        string metodoPago;
        string detalle;
    }

    struct TransaccionPago
    {
        string ordenId;
        string claveIdempotencia;
        long montoCentavos;
        string moneda;
        DatosPago datos;
    }

    struct ResultadoPago
    {
        string ordenId;
        string metodoPago;
        bool aprobado;
        string referencia;
        string motivo;
    }

    exception CompraInvalida
    {
        string motivo;
    }

    exception MetodoNoDisponible
    {
        string metodo;
        string disponibles;
    }

    exception PagoInvalido
    {
        string motivo;
    }

    exception ServicioNoDisponible
    {
        string motivo;
    }

    interface ObservadorOrden
    {
        void ordenActualizada(ResumenOrden orden);
    }

    interface IGestionCompras
    {
        ResumenOrden crearCompra(SolicitudCompra solicitud, ObservadorOrden* observador)
            throws CompraInvalida, MetodoNoDisponible, ServicioNoDisponible;
        ResumenOrden consultarOrden(string ordenId) throws CompraInvalida;
    }

    interface INotificacionPago
    {
        void notificarResultado(ResultadoPago resultado);
    }

    interface IEstrategiaPago
    {
        string aceptarPago(TransaccionPago transaccion, INotificacionPago* notificador)
            throws PagoInvalido;
    }

    interface IPersistenciaTransaccional
    {
        void registrarOrden(ResumenOrden orden, string clienteId, Items items);
        bool registrarResultadoPago(ResultadoPago resultado);
        bool marcarError(string ordenId, string motivo);
        ResumenOrden obtenerOrden(string ordenId) throws CompraInvalida;
    }
}
