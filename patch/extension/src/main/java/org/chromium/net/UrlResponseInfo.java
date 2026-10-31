// COMPILE-ONLY STUB — see CronetEngine.java. Never packaged.
package org.chromium.net;

import java.util.List;
import java.util.Map;

public abstract class UrlResponseInfo {
    public abstract int getHttpStatusCode();
    public abstract Map<String, List<String>> getAllHeaders();
    public abstract String getNegotiatedProtocol();
}
