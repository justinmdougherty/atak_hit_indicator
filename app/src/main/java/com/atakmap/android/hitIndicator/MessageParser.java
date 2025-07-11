package com.atakmap.android.hitIndicator;

import android.util.Log;

import com.atakmap.coremap.maps.coords.GeoPoint;

public class MessageParser {
    private static final String TAG = "MessageParser";

    // Message markers
    public static final byte START_BYTE = (byte) '<';
    public static final byte END_BYTE = (byte) '>';

    // Message types
    public static final String TYPE_POSITION = "POS";
    public static final String TYPE_HIT = "HIT";
    public static final String TYPE_SHOT_FIRED = "SHOT"; // New message type for rifle shot detection
    public static final String TYPE_CALIBRATE_ACK = "CALACK";

    // Outgoing message types
    public static final String TYPE_QUERY = "QUERY";
    public static final String TYPE_CALIBRATE = "CAL";
    public static final String TYPE_READY = "READY";

    public interface MessageListener {
        void onPositionMessage(String id, GeoPoint location, double voltage);

        void onPositionMessageEnhanced(String id, GeoPoint location, double voltage, int satellites, double hdop,
                String altitudeRef);

        void onHitMessage(String id);

        void onShotFiredMessage(String targetId, long timestamp); // New callback for shot detection

        void onCalibrationResponse(String id, long roundTripTime);

        void onParseError(String error);
    }

    private final MessageListener listener;
    private final StringBuilder buffer = new StringBuilder();
    private boolean inMessage = false;

    public MessageParser(MessageListener listener) {
        this.listener = listener;
    }

    public void processData(byte[] data) {
        for (byte b : data) {
            if (b == START_BYTE) {
                // Start of a new message
                inMessage = true;
                buffer.setLength(0); // Clear buffer
            } else if (b == END_BYTE) {
                // End of message - process it
                inMessage = false;
                processMessage(buffer.toString());
            } else if (inMessage) {
                // Regular character within a message
                buffer.append((char) b);
            }
        }
    }

    private void processMessage(String message) {
        try {
            String[] parts = message.split(",");

            if (parts.length == 0) {
                notifyError("Empty message");
                return;
            }

            String messageType = parts[0];

            switch (messageType) {
                case TYPE_POSITION:
                    processPositionMessage(parts);
                    break;

                case TYPE_HIT:
                    processHitMessage(parts);
                    break;

                case TYPE_SHOT_FIRED:
                    processShotFiredMessage(parts);
                    break;

                case TYPE_CALIBRATE_ACK:
                    processCalibrationAck(parts);
                    break;

                default:
                    notifyError("Unknown message type: " + messageType);
                    break;
            }

        } catch (Exception e) {
            notifyError("Error parsing message: " + e.getMessage());
        }
    }

    private void processPositionMessage(String[] parts) {
        // Support both old format: POS,ID,LAT,LON,ALT,BATT
        // and new enhanced format: POS,ID,LAT,LON,ALT,BATT,SATS,HDOP,ALTREF
        if (parts.length < 6) {
            notifyError("Invalid position message format - minimum 6 parts required");
            return;
        }

        try {
            String id = parts[1];
            double lat = Double.parseDouble(parts[2]);
            double lon = Double.parseDouble(parts[3]);
            double alt = Double.parseDouble(parts[4]);
            double voltage = Double.parseDouble(parts[5]);

            GeoPoint location = new GeoPoint(lat, lon, alt);

            // Check if enhanced format with GPS quality data
            if (parts.length >= 9) {
                int satellites = Integer.parseInt(parts[6]);
                double hdop = Double.parseDouble(parts[7]);
                String altitudeRef = parts[8]; // "MSL" or "HAE"

                Log.d(TAG, String.format("Enhanced position: %s, Sats: %d, HDOP: %.1f, AltRef: %s",
                        id, satellites, hdop, altitudeRef));

                if (listener != null) {
                    listener.onPositionMessageEnhanced(id, location, voltage, satellites, hdop, altitudeRef);
                }
            } else {
                // Legacy format
                Log.d(TAG, "Legacy position message for: " + id);
                if (listener != null) {
                    listener.onPositionMessage(id, location, voltage);
                }
            }

        } catch (NumberFormatException e) {
            notifyError("Invalid position coordinates: " + e.getMessage());
        }
    }

    private void processHitMessage(String[] parts) {
        if (parts.length < 2) {
            notifyError("Invalid hit message format");
            return;
        }

        String id = parts[1];

        if (listener != null) {
            listener.onHitMessage(id);
        }
    }

    private void processShotFiredMessage(String[] parts) {
        if (parts.length < 3) {
            notifyError("Invalid shot fired message format - expected: SHOT,targetId,timestamp");
            return;
        }

        String targetId = parts[1];
        long timestamp;

        try {
            timestamp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            notifyError("Invalid timestamp in shot fired message");
            return;
        }

        Log.d(TAG, "Shot fired message received: target=" + targetId + ", timestamp=" + timestamp);

        if (listener != null) {
            listener.onShotFiredMessage(targetId, timestamp);
        }
    }

    private void processCalibrationAck(String[] parts) {
        if (parts.length < 2) {
            notifyError("Invalid calibration ack format");
            return;
        }

        String id = parts[1];
        long roundTripTime = System.currentTimeMillis(); // Will be calculated by caller

        if (listener != null) {
            listener.onCalibrationResponse(id, roundTripTime);
        }
    }

    public static byte[] createQueryMessage() {
        return createMessage(TYPE_QUERY);
    }

    public static byte[] createCalibrationMessage(String id) {
        return createMessage(TYPE_CALIBRATE + "," + id);
    }

    public static byte[] createReadyMessage(String id) {
        return createMessage(TYPE_READY + "," + id);
    }

    public static byte[] createShotExpectedMessage(String targetId, long timestamp) {
        return createMessage("EXPECT," + targetId + "," + timestamp);
    }

    public static byte[] createBallisticsRequestMessage(String targetId) {
        return createMessage("BALLISTICS," + targetId);
    }

    private static byte[] createMessage(String content) {
        byte[] contentBytes = content.getBytes();
        byte[] message = new byte[contentBytes.length + 2]; // +2 for START and END bytes

        message[0] = START_BYTE;
        System.arraycopy(contentBytes, 0, message, 1, contentBytes.length);
        message[message.length - 1] = END_BYTE;

        return message;
    }

    private void notifyError(String error) {
        Log.e(TAG, error);
        if (listener != null) {
            listener.onParseError(error);
        }
    }
}