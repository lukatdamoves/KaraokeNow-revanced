package app.karaokenow.extension;

/** Endpoint + user settings for one karaoke session.
 *
 *  BYOK rule (mirrors the server): the endpoint is the user's OWN
 *  Modal deployment URL, saved on-device at first K tap (see
 *  KaraokeBridge's setup dialog). There is NO hardcoded server:
 *  a null endpoint means "not set up yet" and sessions refuse to
 *  start. The modal key is the karaoke-server API key: it is sent
 *  in the WS hello as "key" when present (keyed servers require
 *  it); null means keyless, which keyless BYOK servers accept.
 *  It is never logged and this class has no toString that prints
 *  it, on purpose.
 */
public class KaraokeSettings {

    /** The user's own Modal server WS URL (wss://host/ws). */
    public final String wsUrl;
    /** Karaoke-server API key, nullable. Never log this value. */
    public final String modalKey;
    /** Constant Bluetooth/output latency compensation, milliseconds.
     *  User-adjustable; 0 by default. Added to the video position
     *  when computing drift. */
    public final long bluetoothOffsetMs;
    /** Stage-2 toggle (shipped): the server streams a real
     *  "backing" stem beside "instrumental", and the player
     *  mixes it live when this is on. */
    public final boolean backingVocalsEnabled;

    public KaraokeSettings(String wsUrl, String modalKey,
            long bluetoothOffsetMs, boolean backingVocalsEnabled) {
        this.wsUrl = wsUrl;
        this.modalKey = modalKey;
        this.bluetoothOffsetMs = bluetoothOffsetMs;
        this.backingVocalsEnabled = backingVocalsEnabled;
    }

    public KaraokeSettings(String wsUrl) {
        this(wsUrl, null, 0, false);
    }
}
