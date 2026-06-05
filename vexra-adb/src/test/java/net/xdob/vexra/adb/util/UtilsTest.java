package net.xdob.vexra.adb.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void encodesLongInLittleEndianOrder() {
        assertArrayEquals(new byte[] {8, 7, 6, 5, 4, 3, 2, 1}, Utils.encodeLong(0x0102030405060708L));
    }

    @Test
    void decodesLongAndHandlesNull() {
        Optional<Long> decoded = Utils.decodeLong(Utils.encodeLong(-42L));

        assertEquals(Long.valueOf(-42L), decoded.get());
        assertFalse(Utils.decodeLong(null).isPresent());
    }

    @Test
    void rejectsInvalidCounterLength() {
        assertThrows(IllegalArgumentException.class, () -> Utils.decodeLong(new byte[] {1, 2, 3}));
    }
}
