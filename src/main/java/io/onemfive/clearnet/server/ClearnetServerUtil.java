package io.onemfive.clearnet.server;

import io.onemfive.core.util.SystemVersion;

import java.awt.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Logger;

public class ClearnetServerUtil {

    private static Logger LOG = Logger.getLogger(ClearnetServerUtil.class.getName());

    public static void launchBrowser(String url) {
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
}
