package eu.kalafatic.tryst.core.config;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Utility to inspect network interfaces and detect the authoritative LAN IP address.
 */
public final class NetworkAddressDetector {

    private NetworkAddressDetector() {}

    /**
     * Resolves the primary LAN IPv4 address of the local machine.
     * Looks for non-loopback, active, site-local IPv4 addresses (e.g. 192.168.x.x, 10.x.x.x, 172.16-31.x.x).
     * If multiple are found, prefers physical interfaces (eth*, en*, wlan*, wl*) over virtual interfaces (docker*, veth*).
     * Falls back to "127.0.0.1" if no suitable LAN interface is found.
     */
    public static String detectLanIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return "127.0.0.1";
            }

            String fallbackCandidate = null;

            for (NetworkInterface nif : Collections.list(interfaces)) {
                if (!nif.isUp() || nif.isLoopback() || nif.isPointToPoint()) {
                    continue;
                }

                String name = nif.getName().toLowerCase();
                boolean isVirtual = name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-") || name.startsWith("virbr");

                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                for (InetAddress addr : Collections.list(addresses)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (addr.isSiteLocalAddress()) {
                            if (!isVirtual) {
                                return ip; // Preferred primary LAN interface (e.g. eth0, wlan0)
                            } else if (fallbackCandidate == null) {
                                fallbackCandidate = ip;
                            }
                        } else if (fallbackCandidate == null) {
                            fallbackCandidate = ip;
                        }
                    }
                }
            }

            if (fallbackCandidate != null) {
                return fallbackCandidate;
            }
        } catch (Exception e) {
            TrystLog.warn(NetworkAddressDetector.class, "Failed to detect LAN network interfaces: " + e.getMessage());
        }

        return "127.0.0.1";
    }
}
