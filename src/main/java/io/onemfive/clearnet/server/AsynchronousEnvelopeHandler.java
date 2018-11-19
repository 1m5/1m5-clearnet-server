package io.onemfive.clearnet.server;

import io.onemfive.data.Envelope;
import org.eclipse.jetty.server.Handler;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public interface AsynchronousEnvelopeHandler extends Handler {
    void setSensor(ClearnetServerSensor sensor);
    void setServiceName(String serviceName);
    void setParameters(String[] parameters);
    void reply(Envelope envelope);
}
