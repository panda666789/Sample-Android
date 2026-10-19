package com.tsinghua.sample.media.sync;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

public class AvcSampleIdentityTest {
    private static final byte[] SPS = {0x67, 0x42, 0, 0x1f};
    private static final byte[] PPS = {0x68, (byte) 0xce, 0x06};
    private static final byte[] SEI = {0x06, 0x05, (byte) 0x80};
    private static final byte[] FRAME = {0x65, 0x12, 0, 0, 3, 1, (byte) 0x80};

    @Test
    public void annexBAndAvccHaveSameCanonicalIdentityIncludingAllNalTypes() {
        String avcc = digest(avcc(SPS, PPS, SEI, FRAME));
        assertEquals(avcc, digest(annexB(4, SPS, PPS, SEI, FRAME)));
        assertEquals(avcc, digest(annexB(3, SPS, PPS, SEI, FRAME)));
        assertEquals(64, avcc.length());
        assertNotEquals(avcc, digest(avcc(PPS, SEI, FRAME)));
        assertNotEquals(avcc, digest(avcc(SPS, SEI, FRAME)));
        assertNotEquals(avcc, digest(avcc(SPS, PPS, FRAME)));
    }

    @Test
    public void canonicalDigestUsesBigEndianFourByteLengthBeforeEachNal() {
        assertEquals("465a54d3dfa9e7a024a056626399bbfb512186e5c5e83e51929842a4faf8a217",
                digest(avcc(new byte[]{0x65, (byte) 0x80})));
    }

    @Test
    public void orderAndPayloadChangeIdentity() {
        String original = digest(avcc(SPS, PPS, SEI, FRAME));
        assertNotEquals(original, digest(avcc(SPS, SEI, PPS, FRAME)));
        byte[] changed = FRAME.clone();
        changed[1]++;
        assertNotEquals(original, digest(avcc(SPS, PPS, SEI, changed)));
    }

    @Test
    public void hashesOnlyRemainingBytesWithoutChangingDirectBufferState() {
        byte[] sample = avcc(SPS, PPS, FRAME);
        ByteBuffer buffer = ByteBuffer.allocateDirect(sample.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0xff).put((byte) 0xee).put(sample).putShort((short) 0xffff);
        buffer.position(2).limit(2 + sample.length).mark();
        assertEquals(digest(sample), AvcSampleIdentity.sha256(buffer));
        assertEquals(2, buffer.position());
        assertEquals(2 + sample.length, buffer.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order());
        buffer.reset();
        assertEquals(2, buffer.position());
        assertEquals(digest(sample), AvcSampleIdentity.sha256(buffer.asReadOnlyBuffer()));
    }

    @Test
    public void firstAvccNalLengthOneIsResolvedUsingCompleteLengthParsing() {
        byte[] oneByteNal = {0x09};
        String expected = digest(annexB(3, oneByteNal, FRAME));
        assertEquals(expected, digest(avcc(oneByteNal, FRAME)));
        assertEquals(digest(annexB(3, oneByteNal)), digest(avcc(oneByteNal)));
    }

    @Test
    public void acceptsMixedThreeAndFourByteStartCodes() {
        ByteArrayOutputStream mixed = new ByteArrayOutputStream();
        append(mixed, annexB(3, SPS));
        append(mixed, annexB(4, PPS));
        append(mixed, annexB(3, SEI));
        append(mixed, annexB(4, FRAME));
        assertEquals(digest(avcc(SPS, PPS, SEI, FRAME)), digest(mixed.toByteArray()));
    }

    @Test
    public void rejectsEmptyTruncatedOrZeroLengthAvccInsteadOfHashingPartialData() {
        assertInvalid(new byte[0]);
        assertInvalid(new byte[]{0, 0, 0});
        assertInvalid(new byte[]{0, 0, 0, 0});
        assertInvalid(new byte[]{0, 0, 0, 8, 0x65, 0x12});
        assertInvalid(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x65});
        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        append(truncated, avcc(SPS));
        append(truncated, new byte[]{0, 0, 0, 8, 0x65});
        assertInvalid(truncated.toByteArray());
    }

    @Test
    public void truncatedAvccAfterOneByteNalIsNotReinterpretedAsAnnexB() {
        assertInvalid(new byte[]{0, 0, 0, 1, 0x09, 0, 0, 0, 8, 0x65});
    }

    @Test
    public void rejectsMissingAnnexBPayloadOrLeadingJunk() {
        assertInvalid(new byte[]{0, 0, 1});
        assertInvalid(new byte[]{0, 0, 0, 1});
        assertInvalid(new byte[]{0, 0, 1, 0, 0, 1, 0x65});
        assertInvalid(new byte[]{0, 0, 1, 0x65, 0, 0, 1});
        assertInvalid(new byte[]{0x55, 0, 0, 1, 0x65});
    }

    @Test
    public void rejectsForbiddenNalHeaderAndUnescapedStartCodeEmulation() {
        assertInvalid(avcc(new byte[]{(byte) 0xe5, 0x12}));
        assertInvalid(annexB(3, new byte[]{0x65, 0, 0, 2, 0x12}));
    }

    @Test
    public void annexBTrailingFramingZerosDoNotChangeNalIdentity() {
        byte[] nal = {0x65, (byte) 0x80};
        assertEquals(digest(avcc(nal)),
                digest(new byte[]{0, 0, 0, 1, 0x65, (byte) 0x80, 0, 0}));
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        append(padded, annexB(3, nal));
        append(padded, new byte[8]);
        assertEquals(digest(avcc(nal)), digest(padded.toByteArray()));
    }

    @Test
    public void annexBLeadingAndInterNalPaddingPreserveEveryNal() {
        ByteArrayOutputStream padded = new ByteArrayOutputStream();
        append(padded, new byte[5]);
        append(padded, annexB(4, SPS));
        append(padded, new byte[6]);
        append(padded, annexB(3, PPS));
        append(padded, new byte[2]);
        append(padded, annexB(4, SEI));
        append(padded, new byte[7]);
        append(padded, annexB(3, FRAME));
        append(padded, new byte[4]);
        assertEquals(digest(avcc(SPS, PPS, SEI, FRAME)), digest(padded.toByteArray()));
    }

    @Test
    public void avccLengthDelimitedPayloadZerosAreNotStripped() {
        assertNotEquals(digest(avcc(new byte[]{0x65, (byte) 0x80})),
                digest(avcc(new byte[]{0x65, (byte) 0x80, 0, 0})));
    }

    @Test
    public void paddingDoesNotMakeMissingNalPayloadOrNonzeroLeadingJunkValid() {
        assertInvalid(new byte[10]);
        assertInvalid(new byte[]{0, 0, 0, 0, 1, 0, 0, 0});
        assertInvalid(new byte[]{0, 0, 0x55, 0, 0, 1, 0x65});
        assertInvalid(new byte[]{0, 0, 0, 1, 0x65, (byte) 0x80, 0, 0, 0, 0, 1});
    }

    private static void assertInvalid(byte[] bytes) {
        assertThrows(IllegalArgumentException.class, () -> digest(bytes));
    }

    private static String digest(byte[] bytes) {
        return AvcSampleIdentity.sha256(ByteBuffer.wrap(bytes));
    }

    private static byte[] avcc(byte[]... nals) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            int n = nal.length;
            output.write(n >>> 24);
            output.write(n >>> 16);
            output.write(n >>> 8);
            output.write(n);
            append(output, nal);
        }
        return output.toByteArray();
    }

    private static byte[] annexB(int prefixSize, byte[]... nals) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            for (int i = 1; i < prefixSize; i++) output.write(0);
            output.write(1);
            append(output, nal);
        }
        return output.toByteArray();
    }

    private static void append(ByteArrayOutputStream output, byte[] bytes) {
        output.write(bytes, 0, bytes.length);
    }
}
