package io.onemfive.clearnet.server;

import io.onemfive.data.Envelope;
import org.eclipse.jetty.server.Handler;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public interface AsynchronousHandler extends Handler {
    void reply(Envelope envelope);
}
