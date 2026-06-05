package net.xdob.vexra.adb.key;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class KeyTest {

    @Test
    void copiesBytesWhenExporting() {
        Key key = new Key(new byte[] {1, 2, 3});
        byte[] exported = key.toBytes();

        exported[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, key.toBytes());
    }

    @Test
    void equalityAndHashUseBytes() {
        Key first = new Key(new byte[] {1, 2, 3});
        Key second = new Key(new byte[] {1, 2, 3});
        Key third = new Key(new byte[] {1, 2, 4});

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, third);
    }

    @Test
    void flipsSignBitForLongAndInt() {
        assertEquals(0L, Key.flipSign(Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, Key.flipSign(0L));
        assertEquals(0, Key.flipSign(Integer.MIN_VALUE));
        assertEquals(Integer.MIN_VALUE, Key.flipSign(0));
    }
}
