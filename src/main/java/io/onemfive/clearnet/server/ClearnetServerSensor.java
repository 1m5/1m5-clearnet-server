package io.onemfive.clearnet.server;

import io.onemfive.core.Config;
import io.onemfive.core.util.SystemVersion;
import io.onemfive.sensors.BaseSensor;
import io.onemfive.sensors.SensorManager;
import io.onemfive.data.Envelope;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.logging.Logger;

/**
 * Sets up HTTP server listeners.
 * Only localhost (127.0.0.1) is supported.
 *
 * @author objectorange
 */
public final class ClearnetServerSensor extends BaseSensor {

    public static final String HANDLER_ID = "1m5.sensors.clearnet.server.handler.id";

    /**
     * Configuration of Servers in the form name, port, launch on start, concrete implementation of io.onemfive.clearnet.server.AsynchronousHandler|n,...}
     */
    public static final String SERVERS_CONFIG = "1m5.sensors.clearnet.server.config";

    private static final Logger LOG = Logger.getLogger(ClearnetServerSensor.class.getName());

    private boolean isTest = false;

    private final List<Server> servers = new ArrayList<>();
    private final Map<Byte,AsynchronousHandler> handlers = new HashMap<>();
    private Byte nextHandlerId = 0;

    private Properties properties;

    public ClearnetServerSensor(SensorManager sensorManager, Envelope.Sensitivity sensitivity, Integer priority) {
        super(sensorManager, sensitivity, priority);
    }

    Byte registerHandler(AsynchronousHandler handler) {
        handlers.put(nextHandlerId++, handler);
        return nextHandlerId;
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
        Byte handlerId = (Byte)e.getHeader(HANDLER_ID);
        if(handlerId == null) {
            LOG.warning("Handler id="+handlerId+" not found in Envelope header. Ensure this is placed in the Envelope header="+HANDLER_ID);
            sensorManager.suspend(e);
            return false;
        }
        AsynchronousHandler handler = handlers.get(handlerId);
        if(handler == null) {
            LOG.warning("Handler with id="+handlerId+" not registered. Please ensure it's registered prior to calling send().");
            sensorManager.suspend(e);
            return false;
        }

        return true;
    }

    @Override
    public boolean start(Properties p) {
        LOG.info("Starting...");
        try {
            properties = Config.loadFromClasspath("clearnet-server.config", p, false);
        } catch (Exception e) {
            LOG.warning(e.getLocalizedMessage());
        }

        if("true".equals(properties.getProperty(Config.PROP_UI))) {
            // Start HTTP Server for 1M5 UI
            AsynchronousHandler handler = new HttpEnvelopeHandler(this);
            ContextHandler context = new ContextHandler();
            context.setContextPath("/");
            context.setHandler(handler);
            boolean launchOnStart = "true".equals(properties.getProperty(Config.PROP_UI_LAUNCH_ON_START));
            // 571 BC - Birth of Laozi, Chinese Philosopher and Writer, author of Tao Te Ching
            if(!startServer("1M5", 571, context, launchOnStart))
                return false;
        }

        if(properties.getProperty(SERVERS_CONFIG)!=null) {
            String serversConfig = properties.getProperty(SERVERS_CONFIG);
            String[] servers = serversConfig.split("|");
            for(String s : servers) {
                String[] m = s.split(",");
                String name = m[0];
                if(name==null){
                    LOG.warning("Name must be provided for HTTP server.");
                    continue;
                }
                String portStr = m[1];
                if(portStr==null){
                    LOG.warning("Port must be provided for HTTP server with name="+name);
                    continue;
                }
                int port = Integer.parseInt(portStr);
                String handlerStr = m[2];
                if(handlerStr==null){
                    LOG.warning("Handler must be provided for HTTP server with name="+name);
                    continue;
                }
                AsynchronousHandler handler = null;
                try {
                    handler = (AsynchronousHandler)Class.forName(handlerStr).newInstance();
                } catch (InstantiationException e) {
                    LOG.warning("Handler must be implementation of io.onemfive.clearnet.server.AsynchronousHandler");
                    continue;
                } catch (IllegalAccessException e) {
                    LOG.warning("Getting an IllegalAccessException while attempting to instantiate Handler implementation class "+handlerStr+". Launch application with appropriate read access.");
                    continue;
                } catch (ClassNotFoundException e) {
                    LOG.warning("Handler implementation "+handlerStr+" not found. Ensure library included.");
                    continue;
                }
                String launchOnStartStr = m[3];
                boolean launchOnStart = "true".equals(launchOnStartStr);

                if(!startServer(name, port, handler, launchOnStart)) {
                    LOG.warning("Unable to start server "+name);
                }
            }
        }

        LOG.info("Started.");
        return true;
    }

    public boolean startServer(String name, int port, Handler handler, boolean launch) {
        Server server = new Server(new InetSocketAddress("127.0.0.1", port));
        server.setHandler(handler);
        try {
            server.start();
            servers.add(server);
            LOG.info("HTTP Server for "+name+" UI started.\n    Host: 127.0.0.1\n    Port: "+port+"\n    Handler: "+handler.getClass().getName());
        } catch (Exception e) {
            LOG.warning("Exception caught while starting HTTP Server for 1M5 UI: "+e.getLocalizedMessage());
            e.printStackTrace();
            return false;
        }
//        server.dumpStdErr();
//        try {
//            The use of server.join() the will make the current thread join and
//            wait until the server is done executing.
//                    See
//            http://docs.oracle.com/javase/7/docs/api/java/lang/Thread.html#join()
//            server.join();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        if(launch)
            launchBrowser("http://127.0.0.1:"+port+"/");
        return true;
    }

    private void launchBrowser(String url) {
        String[] cmd = null;
        if(SystemVersion.isLinuxService()) {
            LOG.info("OS is Linux.");
            String[] browsers = { "epiphany", "firefox", "mozilla", "konqueror",
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
            cmd = new String[]{"rundll32 url.dll,FileProtocolHandler " + url};
        }

        if(cmd != null) {
            try {
                Runtime.getRuntime().exec(cmd);
            } catch (IOException e) {
                e.printStackTrace();
            }
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
        return false;
    }

    @Override
    public boolean shutdown() {
        for(Server server : servers) {
            try {
                server.stop();
            } catch (Exception e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
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
