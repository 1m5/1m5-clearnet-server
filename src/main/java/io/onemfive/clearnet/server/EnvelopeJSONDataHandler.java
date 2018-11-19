package io.onemfive.clearnet.server;

import io.onemfive.sensors.SensorsService;
import io.onemfive.data.DocumentMessage;
import io.onemfive.data.Envelope;
import io.onemfive.data.util.DLC;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.DefaultHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handles incoming requests by:
 *  - creating new Envelope from incoming deserialized JSON request
 *  - sending Envelope to the bus
 *  - blocking until a response is returned
 *  - serializing the Envelope into JSON
 *  - setting up Response letting it return
 *
 * @author objectorange
 */
public class EnvelopeJSONDataHandler extends DefaultHandler implements AsynchronousEnvelopeHandler {

    private static Logger LOG = Logger.getLogger(EnvelopeJSONDataHandler.class.getName());

    protected ClearnetServerSensor sensor;
    private Map<Long,ClientHold> requests = new HashMap<>();
    private Byte id;
    private String serviceName;

    public EnvelopeJSONDataHandler() {}

    public void setSensor(ClearnetServerSensor sensor) {
        this.sensor = sensor;
        id = sensor.registerHandler(this);
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Handles incoming requests by:
     *  - creating new Envelope from incoming deserialized JSON request
     *  - sending Envelope to the bus
     *  - blocking until a response is returned
     *  - serializing the Envelope into JSON
     *  - setting up Response letting it return
     * @param target the path sent after the ip address + port
     * @param baseRequest
     * @param request
     * @param response
     * @throws IOException
     * @throws ServletException
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        LOG.info("HTTP Handler called; target: "+target);
        if("/".equals(target)) {
            response.setContentType("text/html");
            response.getWriter().print("<html><body>"+serviceName+" Available</body></html>");
            response.setStatus(200);
            baseRequest.setHandled(true);
            return;
        }
        int verifyStatus = verifyRequest(target, request);
        if(verifyStatus != 200) {
            response.setStatus(verifyStatus);
            baseRequest.setHandled(true);
            return;
        }
        Envelope envelope = parseEnvelope(target, request);
        ClientHold clientHold = new ClientHold(target, baseRequest, request, response, envelope);
        requests.put(envelope.getId(), clientHold);

//         Add Routes Last first as it's a stack
        DLC.addRoute(SensorsService.class, SensorsService.OPERATION_REPLY, envelope);

        route(envelope); // asynchronous call upon; returns upon reaching Message Channel's queue in Service Bus

        if(DLC.getErrorMessages(envelope).size() > 0) {
            // Just 500 for now
            LOG.warning("Returning HTTP 500...");
            response.setStatus(500);
            baseRequest.setHandled(true);
            requests.remove(envelope.getId());
        } else {
            // Hold Thread until response or 30 seconds
            LOG.info("Holding HTTP Request for up to 30 seconds waiting for internal asynch response...");
            clientHold.hold(30 * 1000); // hold for 30 seconds or until interrupted
        }
    }

    protected void route(Envelope e) {
        String command = e.getCommandPath();
        switch(command) {

        }
        sensor.send(e);
    }

    public void reply(Envelope e) {
        LOG.info("Looking up request envelope for internal response...");
        ClientHold hold = requests.get(e.getId());
        unpackEnvelope(e, hold.getResponse());
        hold.baseRequest.setHandled(true);
        LOG.info("Waking sleeping request thread to return response to caller...");
        hold.wake(); // Interrupt sleep to allow thread to return
        LOG.info("Unwinded request call with response.");
    }

    protected int verifyRequest(String target, HttpServletRequest request) {

        return 200;
    }

    protected Envelope parseEnvelope(String target, HttpServletRequest request) {
        LOG.info("Parsing request into Envelope...");
        Envelope e = Envelope.documentFactory();
        // Must set id in header for asynchronous support
        e.setHeader(ClearnetServerSensor.HANDLER_ID, id);
        e.setCommandPath(target);
        try {
            // This is required to ensure the SensorManager knows to return the reply to the ClearnetServerSensor (ends with .json)
            URL url = new URL("http://127.0.0.1"+target+".json");
            e.setURL(url);
        } catch (MalformedURLException e1) {
            LOG.warning(e1.getLocalizedMessage());
        }

        String method = request.getMethod();
        LOG.info("Incoming method: "+method);
        if(method != null) {
            switch (method.toUpperCase()) {
                case "GET": e.setAction(Envelope.Action.VIEW);break;
                case "POST": e.setAction(Envelope.Action.ADD);break;
                case "PUT": e.setAction(Envelope.Action.UPDATE);break;
                case "DELETE": e.setAction(Envelope.Action.REMOVE);break;
                default: e.setAction(Envelope.Action.VIEW);
            }
        } else {
            e.setAction(Envelope.Action.VIEW);
        }

        Enumeration<String> headerNames = request.getHeaderNames();
        while(headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            Enumeration<String> headerValues = request.getHeaders(headerName);
            boolean first = true;
            int i = 2;
            while(headerValues.hasMoreElements()){
                String headerValue = headerValues.nextElement();
                if(first) {
                    e.setHeader(headerName, headerValue);
                    first = false;
                } else {
                    e.setHeader(headerName + Integer.toString(i++), headerValue);
                }
                LOG.info("Incoming header:value="+headerName+":"+headerValue);
            }
        }

        if(e.getContentType() != null && "multipart/form-data".equals(e.getContentType())) {
            try {
                Collection<Part> parts = request.getParts();
                String contentType;
                String name;
                String fileName;
                InputStream is;
                StringBuffer b;
                int k = 0;
                for (Part part : parts) {
                    contentType = part.getContentType();
                    name = part.getName();
                    fileName = part.getSubmittedFileName();
                    is = part.getInputStream();
                    if (is != null) {
                        b = new StringBuffer();
                        int i;
                        char c;
                        while ((i = is.read()) != -1) {
                            c = (char) i;
                            b.append(c);
                        }
                        String content = b.toString();
                        LOG.info("Incoming file content: " + content);
                        if (k == 0)
                            ((DocumentMessage) e.getMessage()).data.get(k++).put(DLC.CONTENT, content);
                        else {
                            Map<String, Object> d = new HashMap<>();
                            d.put(Envelope.HEADER_CONTENT_TYPE, contentType);
                            d.put(DLC.CONTENT, content);
                            ((DocumentMessage) e.getMessage()).data.add(d);
                        }
                    }
                }
            } catch (Exception e1) {
                LOG.warning(e1.getLocalizedMessage());
                e1.printStackTrace();
            }
        }

        String query = request.getQueryString();
        if(query!=null) {
            LOG.info("Incoming query: "+query);
            Map<String,String> queryMap = new HashMap<>();
            String[] nvps = query.split("&");
            for (String nvpStr : nvps) {
                String[] nvp = nvpStr.split("=");
                queryMap.put(nvp[0], nvp[1]);
            }
            DLC.addData(Map.class, queryMap, e);
        }
        e.setExternal(true);

        return e;
    }

    protected void unpackEnvelope(Envelope e, HttpServletResponse response) {
        String command = e.getCommandPath();
        switch(command) {

        }
        String json = "";
        response.setContentType("application/json");
        try {
            response.getWriter().print(json);
            response.setStatus(200);
        } catch (IOException ex) {
            LOG.warning(ex.getLocalizedMessage());
            response.setStatus(500);
        }
    }

    private class ClientHold {
        private Thread thread;
        private String target;
        private Request baseRequest;
        private HttpServletRequest request;
        private HttpServletResponse response;
        private Envelope envelope;

        private ClientHold(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Envelope envelope) {
            this.target = target;
            this.baseRequest = baseRequest;
            this.request = request;
            this.response = response;
            this.envelope = envelope;
        }

        private void hold(long waitTimeMs) {
            thread = Thread.currentThread();
            try {
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                requests.remove(envelope.getId());
            }
        }

        private void wake() {
            thread.interrupt();
        }

        private String getTarget() {
            return target;
        }

        private Request getBaseRequest() {
            return baseRequest;
        }

        private HttpServletRequest getRequest() {
            return request;
        }

        private HttpServletResponse getResponse() {
            return response;
        }

        private Envelope getEnvelope() {
            return envelope;
        }
    }

}
