CREATE TABLE ordenes (
    id              VARCHAR(36) PRIMARY KEY,
    cliente_id      VARCHAR(64)  NOT NULL,
    monto_centavos  BIGINT       NOT NULL CHECK (monto_centavos > 0),
    moneda          VARCHAR(8)   NOT NULL,
    metodo_pago     VARCHAR(32)  NOT NULL,
    estado          VARCHAR(16)  NOT NULL
                    CHECK (estado IN ('PENDIENTE_PAGO', 'PAGADA', 'RECHAZADA', 'FALLIDA')),
    detalle         TEXT,
    creado          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actualizado     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE items_orden (
    id                        SERIAL PRIMARY KEY,
    orden_id                  VARCHAR(36) NOT NULL REFERENCES ordenes(id),
    sku                       VARCHAR(64) NOT NULL,
    cantidad                  INT         NOT NULL CHECK (cantidad > 0),
    precio_unitario_centavos  BIGINT      NOT NULL CHECK (precio_unitario_centavos > 0)
);

CREATE TABLE pagos (
    id          SERIAL PRIMARY KEY,
    orden_id    VARCHAR(36) NOT NULL UNIQUE REFERENCES ordenes(id),
    metodo_pago VARCHAR(32) NOT NULL,
    aprobado    BOOLEAN     NOT NULL,
    referencia  VARCHAR(80),
    motivo      TEXT,
    fecha       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auditoria (
    id        SERIAL PRIMARY KEY,
    orden_id  VARCHAR(36) NOT NULL,
    evento    VARCHAR(40) NOT NULL,
    detalle   TEXT,
    fecha     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auditoria_orden ON auditoria(orden_id);
