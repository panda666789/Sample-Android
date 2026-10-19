package com.tsinghua.sample.media.sync;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Identity of every AVC NAL in a sample, independent of Annex B versus AVCC framing. */
public final class AvcSampleIdentity {
    private AvcSampleIdentity() {}

    /**
     * Hashes only remaining bytes and leaves position, limit, mark and byte order untouched.
     * Complete AVCC (4-byte big-endian lengths) takes precedence over Annex B: the first AVCC
     * length can be 1, which is indistinguishable from a 4-byte start code by prefix alone.
     * Every NAL, including SEI and parameter sets, retains its order and exact payload bytes.
     * Annex B leading/trailing_zero_8bits are framing and are excluded; AVCC payload bytes
     * are always kept according to their declared length.
     * This checks framing and byte-stream escaping, not decoded-picture validity.
     */
    public static String sha256(ByteBuffer sample) {
        if (sample == null || !sample.hasRemaining()) {
            throw new IllegalArgumentException("Empty AVC sample");
        }
        ByteBuffer bytes = sample.duplicate();
        List<Nal> nals;
        try {
            nals = parseAvcc(bytes);
        } catch (IllegalArgumentException notAvcc) {
            nals = parseAnnexB(bytes);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        for (Nal nal : nals) {
            int length = nal.end - nal.start;
            digest.update((byte) (length >>> 24));
            digest.update((byte) (length >>> 16));
            digest.update((byte) (length >>> 8));
            digest.update((byte) length);
            ByteBuffer payload = bytes.duplicate();
            payload.position(nal.start).limit(nal.end);
            digest.update(payload);
        }
        byte[] hash = digest.digest();
        char[] hex = new char[hash.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < hash.length; i++) {
            hex[i * 2] = digits[(hash[i] & 0xff) >>> 4];
            hex[i * 2 + 1] = digits[hash[i] & 0xf];
        }
        return new String(hex);
    }

    private static List<Nal> parseAvcc(ByteBuffer bytes) {
        List<Nal> result = new ArrayList<>();
        int position = bytes.position();
        int limit = bytes.limit();
        while (position < limit) {
            if (limit - position < 4) throw malformed();
            long length = 0;
            for (int i = 0; i < 4; i++) length = (length << 8) | (bytes.get(position++) & 0xff);
            if (length == 0 || length > limit - position) throw malformed();
            int end = position + (int) length;
            validateNal(bytes, position, end);
            result.add(new Nal(position, end));
            position = end;
        }
        return result;
    }

    private static List<Nal> parseAnnexB(ByteBuffer bytes) {
        List<Nal> result = new ArrayList<>();
        int position = bytes.position();
        int limit = bytes.limit();
        // leading_zero_8bits may precede the first start code; nonzero junk is not padding.
        while (position < limit && bytes.get(position) == 0
                && startCodeLength(bytes, position) == 0) position++;
        if (position == limit) throw malformed();
        while (position < limit) {
            int prefix = startCodeLength(bytes, position);
            if (prefix == 0) throw malformed();
            int start = position + prefix;
            int end = start;
            while (end < limit && startCodeLength(bytes, end) == 0) end++;
            // Match Android getNextNALUnit: zeros before the next prefix or sample end
            // belong to Annex B framing, not the length-delimited NAL written to MP4.
            int payloadEnd = end;
            while (payloadEnd > start && bytes.get(payloadEnd - 1) == 0) payloadEnd--;
            validateNal(bytes, start, payloadEnd);
            result.add(new Nal(start, payloadEnd));
            position = end;
        }
        return result;
    }

    private static int startCodeLength(ByteBuffer bytes, int position) {
        int remaining = bytes.limit() - position;
        if (remaining < 3 || bytes.get(position) != 0 || bytes.get(position + 1) != 0) return 0;
        if (remaining >= 4 && bytes.get(position + 2) == 0 && bytes.get(position + 3) == 1) return 4;
        return bytes.get(position + 2) == 1 ? 3 : 0;
    }

    private static void validateNal(ByteBuffer bytes, int start, int end) {
        if (start >= end) throw malformed();
        int header = bytes.get(start) & 0xff;
        if ((header & 0x80) != 0 || (header & 0x1f) == 0) throw malformed();
        int zeros = 0;
        for (int i = start + 1; i < end; i++) {
            int value = bytes.get(i) & 0xff;
            // Unescaped 00 00 {00,01,02} cannot occur inside an AVC NAL. In particular,
            // do not reinterpret a truncated AVCC length field as Annex B payload.
            if (zeros >= 2 && value <= 2) throw malformed();
            zeros = value == 0 ? zeros + 1 : 0;
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Malformed or truncated AVC sample");
    }

    private static final class Nal {
        final int start;
        final int end;

        Nal(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
