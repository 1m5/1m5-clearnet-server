package io.onemfive.clearnet.server;

import io.onemfive.data.DocumentMessage;
import io.onemfive.data.Envelope;
import io.onemfive.data.content.Content;
import io.onemfive.data.util.DLC;
import io.onemfive.data.util.JSONParser;
import io.onemfive.sensors.SensorsService;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.DefaultHandler;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

/**
 * Handles incoming requests by:
 *  - creating new Envelope from incoming HTTP request
 *  - sending Envelope to the bus
 *  - blocking until a response is returned
 *  - deserializing the Envelope back into bytes
 *  - setting up Response letting it return
 *
 * @author objectorange
 */
public class EnvelopeProxyDataHandler extends DefaultHandler implements AsynchronousEnvelopeHandler {

    private static Logger LOG = Logger.getLogger(EnvelopeProxyDataHandler.class.getName());

    protected ClearnetServerSensor sensor;
    protected Map<Long,ClientHold> requests = new HashMap<>();
    private String id;
    private String serviceName;
    private String[] parameters;

    public EnvelopeProxyDataHandler() {

    }

    public void setSensor(ClearnetServerSensor sensor) {
        this.sensor = sensor;
        id = sensor.registerHandler(this);
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setParameters(String[] parameters) {
        this.parameters = parameters;
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
        if("/test".equals(target)) {
            response.setContentType("text/html");
            response.getWriter().print("<html><body>"+serviceName+" Available</body></html>");
            response.setStatus(200);
            baseRequest.setHandled(true);
            return;
        }

        Envelope envelope = parseEnvelope(request);
        ClientHold clientHold = new ClientHold(target, baseRequest, request, response, envelope);
        requests.put(envelope.getId(), clientHold);

        // Add Routes Last first as it's a stack: Setup for return call
        DLC.addRoute(SensorsService.class, SensorsService.OPERATION_SEND, envelope);

        route(envelope); // asynchronous call upon; returns upon reaching Message Channel's queue in Service Bus

        if(DLC.getErrorMessages(envelope).size() > 0) {
            // Just 500 for now
            LOG.warning("Returning HTTP 500...");
            response.setStatus(500);
            baseRequest.setHandled(true);
            requests.remove(envelope.getId());
        } else {
            // Hold Thread until response or 10 minutes
//            LOG.info("Holding HTTP Request for up to 30 seconds waiting for internal asynch response...");
            clientHold.hold(10 * 60 * 1000); // hold for 10 minutes or until interrupted
        }
    }

    protected void route(Envelope e) {
        sensor.send(e);
    }

    public void reply(Envelope e) {
        LOG.info("Reply received...");
        ClientHold hold = requests.get(e.getId());
        if(hold==null) {
            LOG.warning("Hold not found.");
            return;
        }
        HttpServletResponse response = hold.getResponse();
        String body = new String(unpackEnvelopeContent(e));
        try {
            response.getWriter().print(body);
        } catch (IOException ex) {
            LOG.warning(ex.getLocalizedMessage());
            response.setStatus(500);
        }
        hold.baseRequest.setHandled(true);
        LOG.info("Waking sleeping request thread to return response to caller...");
        hold.wake(); // Interrupt sleep to allow thread to return
        LOG.info("Unwinded request call with response.");
    }

    protected Envelope parseEnvelope(HttpServletRequest request) {
        LOG.info("Parsing request into Envelope...");
        Envelope e = Envelope.documentFactory();
        // Flag as LOW for HTTP - this is required to ensure ClearnetServerSensor is selected in reply
        e.setSensitivity(Envelope.Sensitivity.LOW);
        // Must set id in header for asynchronous support
        e.setHeader(ClearnetServerSensor.HANDLER_ID, id);
        String uri = request.getRequestURI();
        LOG.info("URI:"+uri);
        boolean http = uri.startsWith("http://");
        boolean https = uri.startsWith("https://");
        if(!http && !https) {
            if(uri.contains(":443")) {
                uri = "https://" + uri;
            } else {
                uri = "http://" + uri;
            }
        }
        try {
            URL url = new URL(uri);
            e.setURL(url);
        } catch (MalformedURLException e1) {
            LOG.warning(e1.getLocalizedMessage());
        }

        // Populate method
        String method = request.getMethod();
//        LOG.info("Incoming method: "+method);
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

        // Populate headers
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
//                LOG.info("Incoming header:value="+headerName+":"+headerValue);
            }
        }

        // Get file content if sent
        if(e.getContentType() != null && e.getContentType().startsWith("multipart/form-data")) {
        	request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement(""));
            try {
                Collection<Part> parts = request.getParts();
                String contentType;
                String name;
                String fileName;
                long size = 0;
                InputStream is;
                ByteArrayOutputStream b;
                int k = 0;
                for (Part part : parts) {
                    String msg = "Downloading... {";
                    name = part.getName();
                    msg += "\n\tparamName="+name;
                    fileName = part.getSubmittedFileName();
                    msg += "\n\tfileName="+fileName;
                    contentType = part.getContentType();
                    msg += "\n\tcontentType="+contentType;
                    size = part.getSize();
                    msg += "\n\tsize="+size+"\n}";
                    LOG.info(msg);
                    if(size > 1000000) {
                        // 1Mb
                        LOG.warning("Downloading of file with size="+size+" prevented. Max size is 1Mb.");
                        return e;
                    }
                    is = part.getInputStream();
                    if (is != null) {
                        b = new ByteArrayOutputStream();
                        int nRead;
                        byte[] bucket = new byte[16384];
                        while ((nRead = is.read(bucket, 0, bucket.length)) != -1) {
                            b.write(bucket, 0, nRead);
                        }
                        Content content = Content.buildContent(b.toByteArray(), contentType, fileName, true, true);
                        content.setSize(size);
                        if (k == 0) {
                            Map<String, Object> d = ((DocumentMessage) e.getMessage()).data.get(k++);
                            d.put(Envelope.HEADER_CONTENT_TYPE, contentType);
                            d.put(DLC.CONTENT, content);
                        } else {
                            Map<String, Object> d = new HashMap<>();
                            d.put(Envelope.HEADER_CONTENT_TYPE, contentType);
                            d.put(DLC.CONTENT, content);
                            ((DocumentMessage) e.getMessage()).data.add(d);
                        }
                    }
                }
            } catch (Exception e1) {
                LOG.warning(e1.getLocalizedMessage());
            }
        }

        //Get post formData params
        String postFormBody = getPostRequestFormData(request);
        if(!postFormBody.isEmpty()){
            Map<String, Object> bodyMap = (Map<String, Object>) JSONParser.parse(postFormBody);
            DLC.addData(Map.class, bodyMap, e);
        }

        // Get query parameters if present
        String query = request.getQueryString();
        if(query!=null) {
//            LOG.info("Incoming query: "+query);
            Map<String,String> queryMap = new HashMap<>();
            String[] nvps = query.split("&");
            for (String nvpStr : nvps) {
                String[] nvp = nvpStr.split("=");
                queryMap.put(nvp[0], nvp[1]);
            }
            DLC.addData(Map.class, queryMap, e);
        }
        e.setExternal(true);

        // Get post parameters if present and place as content
        Map<String,String[]> m = request.getParameterMap();
        if(m != null && !m.isEmpty()) {
            DLC.addContent(m, e);
        }

        return e;
    }

    protected byte[] unpackEnvelopeContent(Envelope e) {
        return (byte[])DLC.getContent(e);
    }

    public String getPostRequestFormData(HttpServletRequest request)  {
        StringBuilder formData = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                char[] charBuffer = new char[128];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    formData.append(charBuffer, 0, bytesRead);
                }
            }
        } catch (IOException ex) {
            LOG.warning(ex.getLocalizedMessage());
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ex) {
                    LOG.warning(ex.getLocalizedMessage());
                }
            }
        }

        return formData.toString();
    }

    protected class ClientHold {
        private Thread thread;
        private String target;
        private Request baseRequest;
        private HttpServletRequest request;
        private HttpServletResponse response;
        private Envelope envelope;

        public ClientHold(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Envelope envelope) {
            this.target = target;
            this.baseRequest = baseRequest;
            this.request = request;
            this.response = response;
            this.envelope = envelope;
        }

        public void hold(long waitTimeMs) {
            thread = Thread.currentThread();
            try {
                Thread.sleep(waitTimeMs);
            } catch (InterruptedException e) {
                requests.remove(envelope.getId());
            }
        }

        public void wake() {
            thread.interrupt();
        }

        public String getTarget() {
            return target;
        }

        public Request getBaseRequest() {
            return baseRequest;
        }

        public HttpServletRequest getRequest() {
            return request;
        }

        public HttpServletResponse getResponse() {
            return response;
        }

        public Envelope getEnvelope() {
            return envelope;
        }
    }

}
