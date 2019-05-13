package io.onemfive.clearnet.server;

import io.onemfive.data.Envelope;
import io.onemfive.data.EventMessage;
import io.onemfive.data.JSONSerializable;
import io.onemfive.data.util.DLC;
import io.onemfive.data.util.JSONParser;
import io.onemfive.sensors.SensorsService;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public class JSONWebSocket extends WebSocketAdapter {

    private static Logger LOG = Logger.getLogger(JSONWebSocket.class.getName());

    protected ClearnetServerSensor sensor;
    protected Session session;

    public JSONWebSocket(ClearnetServerSensor sensor) {
        this.sensor = sensor;
    }

    @Override
    public void onWebSocketConnect(Session session) {
        LOG.info("+++ Websocket Connect from " + session.getRemoteAddress().getAddress());
        this.session = session;
    }

    @Override
    public void onWebSocketText(String message) {
        if(message != null && !message.equals("keep-alive")) {
            Envelope e = Envelope.eventFactory(EventMessage.Type.TEXT);
            // Flag as LOW for HTTP
            e.setSensitivity(Envelope.Sensitivity.LOW);
            // Add Data
            DLC.addContent(message, e);
            // Add Route
            DLC.addRoute(SensorsService.class, SensorsService.OPERATION_REPLY, e);
            sensor.send(e);
        }
    }

    public void pushEnvelope(Envelope e) {
        EventMessage em = DLC.getEventMessage(e);
        String json = (String)em.getMessage();
        LOG.info("Sending Text Message to browser....");
        try {
            session.getRemote().sendString(json);
            LOG.info("Text message sent.");
        } catch (IOException e1) {
            LOG.warning(e1.getLocalizedMessage());
        }
    }

}
