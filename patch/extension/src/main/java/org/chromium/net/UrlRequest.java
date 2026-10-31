// COMPILE-ONLY STUB — see CronetEngine.java. Never packaged.
package org.chromium.net;

import java.nio.ByteBuffer;

public abstract class UrlRequest {
    public abstract static class Callback {
        public abstract void onRedirectReceived(
                UrlRequest request, UrlResponseInfo info,
                String newLocationUrl) throws Exception;
        public abstract void onResponseStarted(
                UrlRequest request, UrlResponseInfo info)
                throws Exception;
        public abstract void onReadCompleted(
                UrlRequest request, UrlResponseInfo info,
                ByteBuffer byteBuffer) throws Exception;
        public abstract void onSucceeded(
                UrlRequest request, UrlResponseInfo info);
        public void onFailed(UrlRequest request,
                UrlResponseInfo info, CronetException error) {}
        public void onCanceled(UrlRequest request,
                UrlResponseInfo info) {}
    }

    public static abstract class Builder {
        public abstract Builder addHeader(String header, String value);
        public abstract Builder setHttpMethod(String method);
        public abstract UrlRequest build();
    }

    public abstract void start();
    public abstract void read(ByteBuffer buffer);
    public abstract void followRedirect();
    public abstract void cancel();
    public abstract boolean isDone();
}
