package io.onemfive.clearnet.server;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * TODO: Add Description
 *
 * @author objectorange
 */
public class PushSocket extends WebSocketAdapter {

    private static Logger LOG = Logger.getLogger(PushSocket.class.getName());

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
        if (isConnected()) {
            try {
                LOG.info("Pushing binary...");
                // push the binary
                getRemote().sendBytes(ByteBuffer.wrap(payload));
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
    }

    @Override
    public void onWebSocketText(String message) {
        if (isConnected()) {
            try {
                LOG.info("Pushing text message: "+message);
                // push the text message
                getRemote().sendString(message);
            } catch (IOException e) {
                LOG.warning(e.getLocalizedMessage());
            }
        }
    }
}
