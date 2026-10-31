// COMPILE-ONLY STUB — never packaged into the extension dex.
// The build d8's only app/karaokenow/extension/*.class, so this
// class exists purely so javac can type-check our Cronet calls.
// At runtime the class resolves to the REAL Cronet shipped
// inside YouTube (org.chromium.net is present in its dex).
// Signatures must mirror the public Cronet API; a mismatch
// surfaces as a logged NoSuchMethodError, never a silent path.
package org.chromium.net;

import android.content.Context;
import java.util.concurrent.Executor;

public class CronetEngine {
    public static class Builder {
        public Builder(Context context) {}
        public Builder enableHttp2(boolean value) { return this; }
        public Builder enableQuic(boolean value) { return this; }
        public Builder enableBrotli(boolean value) { return this; }
        public CronetEngine build() { return null; }
    }

    public UrlRequest.Builder newUrlRequestBuilder(
            String url, UrlRequest.Callback callback,
            Executor executor) {
        return null;
    }

    public void shutdown() {}
}
