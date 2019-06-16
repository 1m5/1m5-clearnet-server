package io.onemfive.clearnet.server;

import io.onemfive.data.Envelope;
import io.onemfive.data.EventMessage;
import io.onemfive.data.util.DLC;
import io.onemfive.data.util.JSONParser;
import io.onemfive.sensors.SensorsService;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Handles incoming web requests from browsers by sending text within
 * a Low Sensitivity Envelope to the bus.
 *
 * Handles Envelope events from bus Notification Service by using
 * Session to send their event body as text to browser (push).
 *
 * Jetty sets Session through onWebSocketConnect().
 *
 * Feel free to extend overriding onWebSocketText() and pushEnvelope().
 *
 * @author objectorange
 */
public class EnvelopeWebSocket extends WebSocketAdapter {

    private static Logger LOG = Logger.getLogger(EnvelopeWebSocket.class.getName());

    protected ClearnetServerSensor sensor;
    protected Session session;

    public EnvelopeWebSocket() {}

    public EnvelopeWebSocket(ClearnetServerSensor sensor) {
        this.sensor = sensor;
    }

    public void setClearnetServerSensor(ClearnetServerSensor sensor) {
        this.sensor = sensor;
    }

    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);
        LOG.info("+++ WebSocket Connect...");
        this.session = session;
        LOG.info("Host: "+session.getRemoteAddress().getAddress().getCanonicalHostName());
    }

    @Override
    public void onWebSocketText(String message) {
        LOG.info("WebSocket Text received: "+message);
        if(message != null && !message.equals("keep-alive")) {
            LOG.info("Sending WebSocket text receieved to bus...");
            Envelope e = Envelope.eventFactory(EventMessage.Type.TEXT);
            // Flag as LOW for HTTP
            e.setSensitivity(Envelope.Sensitivity.LOW);
            // Add Data
            DLC.addContent(message, e);
            // Add Route
            DLC.addRoute(SensorsService.class, SensorsService.OPERATION_REPLY, e);
            // Send to bus
            sensor.send(e);
        }
    }

    public void pushEnvelope(Envelope e) {
        EventMessage em = DLC.getEventMessage(e);
        Object obj = em.getMessage();
        if(obj instanceof String) {
            String txt = (String)obj;
            LOG.info("Received Text Message to send to browser: " + txt);
            if (session == null) {
                LOG.warning("Jetty WebSocket session not yet established. Unable to send message.");
                return;
            }
            try {
                RemoteEndpoint endpoint = session.getRemote();
                if (endpoint == null) {
                    LOG.warning("No RemoteEndpoint found for current Jetty WebSocket session.");
                } else {
                    LOG.info("Sending text message to browser...");
                    endpoint.sendString(txt);
                    LOG.info("Text message sent to browser.");
                }
            } catch (IOException ex) {
                LOG.warning(ex.getLocalizedMessage());
            }
        } else {
            LOG.warning("Object received not a String and thus not handled by this adapter. Ignoring.");
        }
    }

}
