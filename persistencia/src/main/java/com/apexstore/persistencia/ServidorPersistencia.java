package com.apexstore.persistencia;

import com.apexstore.comun.Log;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

public final class ServidorPersistencia {

    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args, "config/persistencia.cfg")) {
            Properties props = communicator.getProperties();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(props.getProperty("Db.Url"));
            config.setUsername(props.getProperty("Db.User"));
            config.setPassword(props.getProperty("Db.Password"));
            config.setMaximumPoolSize(props.getPropertyAsIntWithDefault("Db.PoolSize", 16));
            config.setAutoCommit(false);

            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                ObjectAdapter adapter = communicator.createObjectAdapter("PersistenciaAdapter");
                adapter.add(new PersistenciaTransaccionalI(dataSource), Util.stringToIdentity("persistencia"));
                adapter.activate();

                Runtime.getRuntime().addShutdownHook(new Thread(communicator::shutdown));
                Log.info("Persistencia", "ServidorPersistencia listo en %s",
                        props.getProperty("PersistenciaAdapter.Endpoints"));
                communicator.waitForShutdown();
            }
        }
    }
}
