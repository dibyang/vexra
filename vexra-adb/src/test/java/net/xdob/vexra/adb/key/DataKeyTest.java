package net.xdob.vexra.adb.key;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.xdob.vexra.adb.db.KeyType;
import org.junit.jupiter.api.Test;

class DataKeyTest {

    @Test
    void rowKeyRoundTripsFromBytes() {
        TabId tabId = TabId.of(7, 99L);
        RowKey rowKey = RowKey.of(tabId, -3L);

        DataKey decoded = DataKey.fromBytes(rowKey.toBytes());

        assertInstanceOf(RowKey.class, decoded);
        assertEquals(tabId, decoded.getTabID());
        assertEquals(-3L, decoded.getRowId());
        assertEquals(KeyType.ROW, decoded.getType());
        assertTrue(decoded.isRow());
        assertFalse(decoded.isIndex());
    }

    @Test
    void indexKeyRoundTripsFromBytesAndDefensivelyCopiesIndex() {
        TabId tabId = TabId.of(8, 100L);
        byte[] index = new byte[] {9, 8, 7};
        IndexKey indexKey = IndexKey.of(tabId, 11, index, Long.MAX_VALUE);

        index[0] = 1;
        IndexKey decoded = (IndexKey) DataKey.fromBytes(indexKey.toBytes());
        byte[] exportedIndex = decoded.getIndex();
        exportedIndex[1] = 1;

        assertEquals(tabId, decoded.getTabID());
        assertEquals(11, decoded.getIndexId());
        assertArrayEquals(new byte[] {9, 8, 7}, decoded.getIndex());
        assertEquals(Long.MAX_VALUE, decoded.getRowId());
        assertEquals(KeyType.INDEX, decoded.getType());
        assertTrue(decoded.isIndex());
        assertFalse(decoded.isRow());
    }

    @Test
    void prefixKeyRoundTripsToConcreteTypes() {
        TabId tabId = TabId.of(9, 101L);

        PrefixKey rowPrefix = PrefixKey.fromBytes(RowPrefix.of(tabId).toBytes());
        PrefixKey indexPrefix = PrefixKey.fromBytes(IndexPrefix.of(tabId, 3).toBytes());

        assertInstanceOf(RowPrefix.class, rowPrefix);
        assertTrue(rowPrefix.isRow());
        assertInstanceOf(IndexPrefix.class, indexPrefix);
        assertTrue(indexPrefix.isIndex());
        assertEquals(3, ((IndexPrefix) indexPrefix).getIndexId());
    }

    @Test
    void rejectsUnknownDataKeyType() {
        byte[] bytes = RowKey.of(TabId.of(1, 1L), 1L).toBytes();
        byte[] invalid = Arrays.copyOf(bytes, bytes.length);
        invalid[TableKey.HEADER_SIZE - 1] = 99;

        assertThrows(IllegalArgumentException.class, () -> DataKey.fromBytes(invalid));
        assertThrows(IllegalArgumentException.class, () -> PrefixKey.fromBytes(invalid));
    }
}
