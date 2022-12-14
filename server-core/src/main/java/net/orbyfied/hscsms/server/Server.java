package net.orbyfied.hscsms.server;

import net.orbyfied.hscsms.common.ProtocolSpec;
import net.orbyfied.hscsms.common.protocol.DisconnectReason;
import net.orbyfied.hscsms.core.ServiceManager;
import net.orbyfied.hscsms.core.resource.ServerResourceManager;
import net.orbyfied.hscsms.db.Database;
import net.orbyfied.hscsms.db.DatabaseManager;
import net.orbyfied.hscsms.db.Login;
import net.orbyfied.hscsms.db.impl.MongoDatabase;
import net.orbyfied.hscsms.network.NetworkManager;
import net.orbyfied.hscsms.network.handler.UtilityNetworkHandler;
import net.orbyfied.hscsms.security.AsymmetricEncryptionProfile;
import net.orbyfied.hscsms.service.Logging;
import net.orbyfied.hscsms.util.worker.SafeWorker;
import net.orbyfied.hscsms.util.Values;
import net.orbyfied.j8.util.logging.Logger;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server {

    /* ------ Core ------ */

    // the server name
    final String name = "hscsms";

    // if the server should be running
    AtomicBoolean active = new AtomicBoolean(false);

    // the logger
    Logger logger = Logging.getLogger("Server");

    // top level configuration
    public final Values configuration = new Values();

    /* ------ Networking ------ */

    // the servers socket address
    SocketAddress address;
    // the server socket
    ServerSocket socket;

    // the server utility network handler
    UtilityNetworkHandler networkHandler;
    // server socket worker
    public final SafeWorker serverSocketWorker = new SafeWorker("ServerSocketWorker")
            .withActivityPredicate(safeWorker -> active.get());

    public UtilityNetworkHandler utilityNetworkHandler() {
        return networkHandler;
    }

    // the currently connected clients
    List<ServerClient> clients = new ArrayList<>();

    /* ------ Security ----- */

    // the top level encryption
    public final AsymmetricEncryptionProfile topLevelEncryption
            = ProtocolSpec.newAsymmetricEncryptionProfile();

    /* ------ Top-Level Services ------ */

    // the service manager
    ServiceManager serviceManager = new ServiceManager();
    // the network manager
    NetworkManager networkManager = new NetworkManager();
    // the database manager
    DatabaseManager databaseManager = new DatabaseManager(this);
    // the resource manager
    ServerResourceManager resourceManager = new ServerResourceManager(this);

    public Server() { }

    public String name() {
        return name;
    }

    /**
     * Bind and open the server on the provided
     * socket address.
     * @param address The socket address.
     * @return This.
     */
    public Server open(SocketAddress address) {
        // set logger stage
        logger.stage("Connect");

        // set address
        this.address = address;

        try {
            // create and bind socket
            socket = new ServerSocket();
            socket.bind(address);

            logger.ok("Connected server on {0}", address);
        } catch (Exception e) {
            logger.err("Failed to connect server on {0}", address);
            e.printStackTrace(Logging.ERR);
        }

        try {
            // create utility network handler
            networkHandler = new UtilityNetworkHandler(networkManager, null)
                    .owned(this);

            // start
            networkHandler.start();

            logger.ok("Started utility network handler");
        } catch (Exception e) {
            logger.err("Failed to start utility network handler");
            e.printStackTrace(Logging.ERR);
        }

        try {
            // load protocol spec
            ProtocolSpec.loadProtocol(networkManager);

            logger.ok("Loaded protocol");
        } catch (Exception e) {
            logger.err("Failed to load protocol");
            e.printStackTrace(Logging.ERR);
        }

        // generate top level key pair
        try {
            topLevelEncryption.generateKeys();
            logger.ok("Generated top level RSA key pair");
        } catch (Exception e) {
            logger.err("Failed to generate top level RSA key pair");
            e.printStackTrace(Logging.ERR);
        }

        // reset stage
        logger.stage(null);

        // return
        return this;
    }

    public Server setup() {
        // setup databases
        Database db;
        Values dbConfig = configuration.get("database", Values.class);
        String brand    = dbConfig.get("brand", String.class).toLowerCase();

        switch (brand) {
            // MongoDB
            case "mongo", "mongodb" -> {
                db = new MongoDatabase(databaseManager, "server");
                db.login(Login.ofURI(
                        dbConfig.get("connection-url"),
                        dbConfig.get("database-name")
                ));
            }

            default -> {
                logger.err("Unsupported database brand: {0}", brand);
                return this;
            }
        }

        databaseManager.addDatabase(db);
        resourceManager.database(databaseManager.getDatabase("server"));

        // setup resource manager
        resourceManager.setup();

        // return
        return this;
    }

    public Server start() {
        // start socket worker
        serverSocketWorker
                .withTarget(this::runMain)
                .start();

        // return
        return this;
    }

    public Server setActive(boolean b) {
        active.set(b);
        return this;
    }

    public boolean isActive() {
        return active.get();
    }

    /**
     * The main server network loop.
     */
    private void runMain() {
        // thread local stage
        logger.stage("Socket");

        // while running
        while (active.get()) {
            // check if the socket is still open
            if (socket.isClosed()) {
                // report and close server
                logger.info("Socket closed, shutting down");
                shutdownProcess();
            }

            try {
                // accept connection (blocking)
                Socket clientSocket = socket.accept();

                try {
                    // construct client
                    final ServerClient client = new ServerClient(this, clientSocket);
                    // register client
                    clients.add(client);
                    // start client worker
                    client.start();

                    // run additional tasks asynchronously
                    CompletableFuture.runAsync(() -> {
                        // ready encryption
                        client.readyTopLevelEncryption();
                    });

                    ServerClient.LOGGER.info("Accepted and started {0}", client);
                } catch (Exception e) {
                    logger.err("Error while accepting connection from [{0}]",
                            ServerClient.toStringAddress(clientSocket));
                }
            } catch (Exception e) {
                logger.err("Error while accepting connections");
                e.printStackTrace(Logging.ERR);
            }
        }

        // shutdown
        logger.info("Server closed, shutting down");
        shutdownProcess();
    }

    public void shutdown() {
        // terminate workers
        serverSocketWorker.terminate();

        // set inactive
        active.set(false);

        // call shutdown process
        shutdownProcess();
    }

    private void shutdownProcess() {
        logger.stage("Shutdown");
        logger.info("Shutting down...");

        // disconnect all clients
        logger.info("Disconnecting clients");
        for (ServerClient client : clients) {
            client.disconnect(DisconnectReason.CLOSE);
            client.stop();
        }

        // close server socket
        if (!socket.isClosed()) {
            try {
                logger.info("Closing server socket");
                socket.close();
            } catch (Exception e) {
                e.printStackTrace(Logging.ERR);
            }
        }

        // close logger group
        Logging.getGroup().setActive(false);
    }

    /* ------ Top-Level Services ------ */

    /**
     * Get the core service manager.
     * @return The service manager.
     */
    public ServiceManager services() {
        return serviceManager;
    }

    /**
     * Get the core network manager.
     * @return The network manager.
     */
    public NetworkManager networkManager() {
        return networkManager;
    }

    /**
     * Get the core server resource manager.
     * @return The resource manager.
     */
    public ServerResourceManager resourceManager() {
        return resourceManager;
    }

    /**
     * Get the core database manager.
     * @return The database manager.
     */
    public DatabaseManager databaseManager() {
        return databaseManager;
    }

}
