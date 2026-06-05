package net.xdob.vexra.adb.key;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VersionKeyTest {

    @Test
    void rowVersionKeyRoundTripsAndRestoresDataKey() {
        TabId tabId = TabId.of(5, 10L);
        RowKey rowKey = RowKey.of(tabId, 123L);

        VersionRowKey versionKey = (VersionRowKey) VersionKey.of(rowKey, false, 456L);
        VersionKey decoded = VersionKey.fromBytes(versionKey.toBytes());

        assertInstanceOf(VersionRowKey.class, decoded);
        assertEquals(tabId, decoded.getTabID());
        assertTrue(decoded.isRow());
        assertFalse(decoded.isIndex());
        assertFalse(decoded.isCommited());
        assertEquals(123L, decoded.getRowId());
        assertEquals(Long.MAX_VALUE - 456L, decoded.getVersion());
        assertEquals(Long.MAX_VALUE - 456L, decoded.getTxnId());
        assertThrows(IllegalStateException.class, decoded::getCommitTs);
        assertEquals(rowKey, decoded.toDataKey());
    }

    @Test
    void indexVersionKeyRoundTripsAndDefensivelyCopiesIndex() {
        TabId tabId = TabId.of(6, 11L);
        byte[] index = new byte[] {1, 2, 3};
        IndexKey indexKey = IndexKey.of(tabId, 4, index, 789L);

        VersionIndexKey versionKey = (VersionIndexKey) VersionKey.of(indexKey, true, 321L);
        VersionIndexKey decoded = (VersionIndexKey) VersionKey.fromBytes(versionKey.toBytes());
        byte[] exportedIndex = decoded.getIndex();
        exportedIndex[0] = 9;

        assertEquals(tabId, decoded.getTabID());
        assertTrue(decoded.isIndex());
        assertTrue(decoded.isCommited());
        assertEquals(4, decoded.getIndexId());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.getIndex());
        assertEquals(789L, decoded.getRowId());
        assertEquals(321L, decoded.getVersion());
        assertEquals(321L, decoded.getCommitTs());
        assertThrows(IllegalStateException.class, decoded::getTxnId);
        assertEquals(indexKey, decoded.toDataKey());
    }

    @Test
    void canRebuildVersionKeyWithNewState() {
        VersionKey uncommitted = VersionRowKey.of(TabId.of(1, 2L), 3L, false, 4L);

        VersionKey committed = VersionKey.of(uncommitted, true, 5L);

        assertTrue(committed.isCommited());
        assertEquals(3L, committed.getRowId());
        assertEquals(Long.MAX_VALUE - 5L, committed.getCommitTs());
    }

    @Test
    void rejectsUnknownVersionKeyType() {
        byte[] bytes = VersionRowKey.of(TabId.of(1, 1L), 1L, true, 1L).toBytes();
        byte[] invalid = Arrays.copyOf(bytes, bytes.length);
        invalid[TableKey.HEADER_SIZE - 1] = 99;

        assertThrows(IllegalArgumentException.class, () -> VersionKey.fromBytes(invalid));
    }
}
