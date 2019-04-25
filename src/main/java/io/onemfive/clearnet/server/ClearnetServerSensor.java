package io.onemfive.clearnet.server;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import io.onemfive.core.notification.NotificationService;
import io.onemfive.core.notification.SubscriptionRequest;
import io.onemfive.data.EventMessage;
import io.onemfive.data.Subscription;
import io.onemfive.data.util.DLC;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.session.SessionHandler;

import io.onemfive.core.Config;
import io.onemfive.core.util.SystemVersion;
import io.onemfive.data.Envelope;
import io.onemfive.sensors.BaseSensor;
import io.onemfive.sensors.SensorManager;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Sets up HTTP server listeners.
 * Only localhost (127.0.0.1) is supported.
 *
 * @author objectorange
 */
public final class ClearnetServerSensor extends BaseSensor {

    public static final String HANDLER_ID = "1m5.sensors.clearnet.server.handler.id";

    /**
     * Configuration of Servers in the form:
     *      name, port, launch on start, concrete implementation of io.onemfive.clearnet.server.AsynchronousEnvelopeHandler, run websocket, relative resource directory|n,...}
     */
    public static final String SERVERS_CONFIG = "1m5.sensors.clearnet.server.config";

    private static final Logger LOG = Logger.getLogger(ClearnetServerSensor.class.getName());

    private boolean isTest = false;

    private final List<Server> servers = new ArrayList<>();
    private JSONWebSocket jsonWebSocket = null;
    private final Map<String,AsynchronousEnvelopeHandler> handlers = new HashMap<>();
    private int nextHandlerId = 1;

    private Properties properties;

    public ClearnetServerSensor() {}

    public ClearnetServerSensor(SensorManager sensorManager, Envelope.Sensitivity sensitivity, Integer priority) {
        super(sensorManager, sensitivity, priority);
    }

    String registerHandler(AsynchronousEnvelopeHandler handler) {
        String nextHandlerIdStr = String.valueOf(nextHandlerId++);
        handlers.put(nextHandlerIdStr, handler);
        return nextHandlerIdStr;
    }

    @Override
    public String[] getOperationEndsWith() {
        return new String[]{".html",".htm",".do",".json"};
    }

    @Override
    public String[] getURLBeginsWith() {
        return new String[]{"http","https"};
    }

    @Override
    public String[] getURLEndsWith() {
        return new String[]{".html",".htm",".do",".json"};
    }

    @Override
    public boolean send(Envelope e) {
        if(!isTest)
            sensorManager.sendToBus(e);
        else {
            // TODO: add content here
            reply(e);
        }

        return true;
    }

    @Override
    public boolean reply(Envelope e) {
        LOG.info("Reply to ClearnetServerSensor; forwarding to registered handler...");
        String handlerId = (String)e.getHeader(HANDLER_ID);
        if(handlerId == null) {
            LOG.warning("Handler id="+handlerId+" not found in Envelope header. Ensure this is placed in the Envelope header="+HANDLER_ID);
            sensorManager.suspend(e);
            return false;
        }
        AsynchronousEnvelopeHandler handler = handlers.get(handlerId);
        if(handler == null) {
            LOG.warning("Handler with id="+handlerId+" not registered. Please ensure it's registered prior to calling send().");
            sensorManager.suspend(e);
            return false;
        }
        handler.reply(e);
        return true;
    }

    @Override
    public boolean start(Properties p) {
        LOG.info("Starting...");
        Config.logProperties(p);
        try {
            properties = Config.loadFromClasspath("clearnet-server.config", p, false);
        } catch (Exception e) {
            LOG.warning(e.getLocalizedMessage());
        }

        if("true".equals(properties.getProperty(Config.PROP_UI))) {
            String webDir = this.getClass().getClassLoader().getResource("io/onemfive/clearnet/server/ui").toExternalForm();
            // Start HTTP Server for 1M5 UI
            AsynchronousEnvelopeHandler dataHandler = new EnvelopeJSONDataHandler();
            dataHandler.setSensor(this);
            dataHandler.setServiceName("1M5-Data-Service");

            ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setDirectoriesListed(false);
            resourceHandler.setResourceBase(webDir);

            ContextHandler dataContext = new ContextHandler();
            dataContext.setContextPath("/data/*");
            dataContext.setHandler(dataHandler);

            HandlerCollection handlers = new HandlerCollection();
            handlers.addHandler(new SessionHandler());
            handlers.addHandler(dataContext);
            handlers.addHandler(resourceHandler);
            handlers.addHandler(new DefaultHandler());

            boolean launchOnStart = "true".equals(properties.getProperty(Config.PROP_UI_LAUNCH_ON_START));
            // 571 BC - Birth of Laozi, Chinese Philosopher and Writer, author of Tao Te Ching
            if(!startServer("1M5", 5710, handlers, launchOnStart))
                return false;
        }

        if(properties.getProperty(SERVERS_CONFIG)!=null) {
            String serversConfig = properties.getProperty(SERVERS_CONFIG);
            LOG.info("Building servers configuration: "+serversConfig);
            String[] servers = serversConfig.split(":");
            LOG.info("Number of servers to start: "+servers.length);
            // TODO: Support multiple servers
//            for(String s : servers) {
            if(servers.length > 0) {
                String s = servers[0];
                HandlerCollection handlers = new HandlerCollection();

                String[] m = s.split(",");
                String name = m[0];
                if(name==null){
                    LOG.warning("Name must be provided for HTTP server.");
                    return false;
                }

                String portStr = m[1];
                if(portStr==null){
                    LOG.warning("Port must be provided for HTTP server with name="+name);
                    return false;
                }
                int port = Integer.parseInt(portStr);

                String launchOnStartStr = m[2];
                boolean launchOnStart = "true".equals(launchOnStartStr);

                String spaStr = m[3];
                boolean spa = "true".equals(spaStr);

                String dataHandlerStr = m[4];
                AsynchronousEnvelopeHandler dataHandler = null;

                String useSocketStr = m[5];

                String resourceDirectory = m[6];
                String webDir = this.getClass().getClassLoader().getResource(resourceDirectory).toExternalForm();

                SessionHandler sessionHandler = new SessionHandler();

                ContextHandler dataContext = new ContextHandler();
                dataContext.setContextPath("/data/*");

                ResourceHandler resourceHandler = new ResourceHandler();
                resourceHandler.setDirectoriesListed(false);
                resourceHandler.setWelcomeFiles(new String[]{"index.html"});
                resourceHandler.setResourceBase(webDir);

                ContextHandler wsContext = null;
                if(useSocketStr!=null && "true".equals(useSocketStr)) {
                    jsonWebSocket = new JSONWebSocket(this);
                    WebSocketHandler wsHandler = new WebSocketHandler() {
                        @Override
                        public void configure(WebSocketServletFactory factory) {
                            WebSocketPolicy policy = factory.getPolicy();
                            // set a 10 second timeout
                            policy.setIdleTimeout(10 * 1000);
//                            policy.setAsyncWriteTimeout(60 * 1000);
//                            int maxSize = 100 * 1000000;
//                            policy.setMaxBinaryMessageSize(maxSize);
//                            policy.setMaxBinaryMessageBufferSize(maxSize);
//                            policy.setMaxTextMessageSize(maxSize);
//                            policy.setMaxTextMessageBufferSize(maxSize);

                            // register PushSocket as the WebSocket to create on Upgrade
//                            factory.register(PushSocket.class);
                            factory.setCreator(new WebSocketCreator() {
                                @Override
                                public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
                                    String query = req.getRequestURI().toString();
                                    if ((query == null) || (query.length() <= 0)) {
                                        try {
                                            resp.sendForbidden("Unspecified query");
                                        } catch (IOException e) {

                                        }
                                        return null;
                                    }
                                    return jsonWebSocket;
                                }
                            });
                        }

                    };
                    wsContext = new ContextHandler();
                    wsContext.setContextPath("/event");
                    wsContext.setHandler(wsHandler);
                }

                handlers.addHandler(sessionHandler);
                if(spa) {
                    handlers.addHandler(new SPAHandler());
                }
                handlers.addHandler(dataContext);
                handlers.addHandler(resourceHandler);
                if(wsContext!=null) {
                    handlers.addHandler(wsContext);
                }
                handlers.addHandler(new DefaultHandler());

                if(dataHandlerStr!=null) { // optional
                    try {
                        dataHandler = (AsynchronousEnvelopeHandler) Class.forName(dataHandlerStr).newInstance();
                        dataHandler.setSensor(this);
                        dataHandler.setServiceName(name);
                        dataHandler.setParameters(m);
                        dataContext.setHandler(dataHandler);
                    } catch (InstantiationException e) {
                        LOG.warning("Data Handler must be implementation of "+AsynchronousEnvelopeHandler.class.getName()+" to ensure asynchronous replies with Envelopes gets returned to calling thread.");
                        return false;
                    } catch (IllegalAccessException e) {
                        LOG.warning("Getting an IllegalAccessException while attempting to instantiate data Handler implementation class " + dataHandlerStr + ". Launch application with appropriate read access.");
                        return false;
                    } catch (ClassNotFoundException e) {
                        LOG.warning("Data Handler implementation " + dataHandlerStr + " not found. Ensure library included.");
                        return false;
                    }
                }

                if(!startServer(name, port, handlers, launchOnStart)) {
                    LOG.warning("Unable to start server "+name);
                } else if(jsonWebSocket != null) {
                    // Subscribe to Text notifications
                    Subscription subscription = new Subscription() {
                        @Override
                        public void notifyOfEvent(Envelope envelope) {
                            jsonWebSocket.pushEnvelope(envelope);
                        }
                    };
                    SubscriptionRequest r = new SubscriptionRequest(EventMessage.Type.TEXT, subscription);
                    Envelope e = Envelope.documentFactory();
                    DLC.addData(SubscriptionRequest.class, r, e);
                    DLC.addRoute(NotificationService.class, NotificationService.OPERATION_SUBSCRIBE, e);
                    send(e);
                }
            }
        }

        LOG.info("Started.");
        return true;
    }

    private boolean startServer(String name, int port, Handler dataHandler, boolean launch) {
        Server server = new Server(new InetSocketAddress("127.0.0.1", port));
        server.setHandler(dataHandler);
        try {
            server.start();
//            LOG.info(server.dump());
            servers.add(server);
            LOG.info("HTTP Server for "+name+" UI started on 127.0.0.1:"+port);
        } catch (Exception e) {
            LOG.severe("Exception caught while starting HTTP Server for "+name+" UI: "+e.getLocalizedMessage());
            e.printStackTrace();
            return false;
        }
        if(launch)
            launchBrowser("http://127.0.0.1:"+port+"/");
        return true;
    }

    private void launchBrowser(String url) {
        String[] cmd = null;
        if(SystemVersion.isLinux()) {
            LOG.info("OS is Linux.");
            String[] browsers = { "purebrowser", "epiphany", "firefox", "mozilla", "konqueror",
                    "netscape", "opera", "links", "lynx" };
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < browsers.length; i++)
                if(i == 0)
                    sb.append(String.format(    "%s \"%s\"", browsers[i], url));
                else
                    sb.append(String.format(" || %s \"%s\"", browsers[i], url));
            // If the first didn't work, try the next browser and so on
            cmd = new String[]{"sh", "-c", sb.toString()};
        } else if(SystemVersion.isMac()) {
            LOG.info("OS is Mac.");
            cmd = new String[]{"open " + url};
        } else if(SystemVersion.isWindows()) {
            LOG.info("OS is Windows.");
            try {
				Desktop.getDesktop().browse(new URL(url).toURI());
			} catch (MalformedURLException e) {
                LOG.severe("MalformedURLException caught while launching browser for windows. Error message: "+e.getLocalizedMessage());
			} catch (IOException e) {
                LOG.severe("IOException caught while launching browser for windows. Error message: "+e.getLocalizedMessage());
			} catch (URISyntaxException e) {
                LOG.severe("URISyntaxException caught while launching browser for windows. Error message: "+e.getLocalizedMessage());
			}
            return;            
        } else {
            LOG.warning("Unable to determine OS therefore unable to launch a browser.");
            return;
        }

        try {
            Runtime.getRuntime().exec(cmd);
        } catch (IOException e) {
            LOG.warning(e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    @Override
    public boolean pause() {
        return false;
    }

    @Override
    public boolean unpause() {
        return false;
    }

    @Override
    public boolean restart() {
        LOG.info("Restarting...");
        for(Server server : servers) {
            try {
                server.stop();
                server.start();
            } catch (Exception e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        LOG.info("Restarted.");
        return true;
    }

    @Override
    public boolean shutdown() {
        LOG.info("Shutting down...");
        for(Server server : servers) {
            try {
                server.stop();
            } catch (Exception e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
        LOG.info("Shutdown.");
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        return shutdown();
    }

    public static void main(String[] args) {
        Properties p = new Properties();
        p.setProperty("1m5.ui","true");
        p.setProperty("1m5.ui.launchOnStart","true");
        ClearnetServerSensor sensor = new ClearnetServerSensor(null, null, null);
        sensor.start(p);
    }

}
