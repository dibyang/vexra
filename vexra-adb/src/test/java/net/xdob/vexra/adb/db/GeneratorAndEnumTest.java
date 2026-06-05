package net.xdob.vexra.adb.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.xdob.vexra.adb.DbStore;
import net.xdob.vexra.adb.key.MetaType;
import org.junit.jupiter.api.Test;

class GeneratorAndEnumTest {

    @Test
    void keyGeneratorAllocatesRowsFromPersistedSegments() throws Exception {
        InMemoryCounterStore store = new InMemoryCounterStore();
        KeyGenerator generator = new KeyGenerator(store, 12);

        assertEquals(1L, generator.nextKey());
        assertEquals(2L, generator.nextKey());
        assertEquals(1000L, store.metaCounterValue());
        assertEquals(1, store.addLongCalls);
    }

    @Test
    void commitTsGeneratorAllocatesCommitTsFromPersistedSegments() {
        InMemoryCounterStore store = new InMemoryCounterStore();
        CommitTSGenerator generator = new CommitTSGenerator(store);

        assertEquals(1L, generator.nextCommitTs());
        assertEquals(2L, generator.nextCommitTs());
        assertEquals(3L, generator.lastCommitTs());
        assertEquals(1000L, store.metaCounterValue());
        assertEquals(1, store.addLongCalls);
    }

    @Test
    void cfAndKeyEnumsMapKnownAndUnknownCodes() {
        assertEquals(Arrays.asList(CF.DEFAULT, CF.META, CF.TXN), CF.allCfs());
        assertEquals(CF.META, CF.of(CF.META.getCfId()));
        assertEquals(CF.UNSPECIFIED, CF.of(99));
        assertEquals(KeyType.ROW, KeyType.getByCode(KeyType.ROW.getCode()));
        assertEquals(MetaType.TXN_COMMIT_TS, MetaType.getByCode(MetaType.TXN_COMMIT_TS.getCode()));
        assertThrows(IllegalArgumentException.class, () -> KeyType.getByCode((byte) 99));
        assertThrows(IllegalArgumentException.class, () -> MetaType.getByCode((byte) 99));
    }

    private static final class InMemoryCounterStore implements DbStore {
        private final Map<String, Long> counters = new HashMap<>();
        private int addLongCalls;

        @Override
        public long addLong(byte cfId, byte[] key, long delta) {
            addLongCalls++;
            String mapKey = key(cfId, key);
            long value = counters.getOrDefault(mapKey, 0L) + delta;
            counters.put(mapKey, value);
            return value;
        }

        @Override
        public Optional<Long> getLong(byte cfId, byte[] key) {
            return Optional.ofNullable(counters.get(key(cfId, key)));
        }

        long metaCounterValue() {
            assertFalse(counters.isEmpty());
            assertTrue(counters.keySet().iterator().next().startsWith(Byte.toString(CF.META.getCfId())));
            return counters.values().iterator().next();
        }

        private static String key(byte cfId, byte[] key) {
            return cfId + ":" + Arrays.toString(key);
        }

        @Override
        public byte[] get(byte[] key) throws SQLException {
            throw unsupported();
        }

        @Override
        public void put(byte[] key, byte[] value) throws SQLException {
            throw unsupported();
        }

        @Override
        public long addLong(byte[] key, long operand) throws SQLException {
            throw unsupported();
        }

        @Override
        public Optional<Long> getLong(byte[] key) throws SQLException {
            throw unsupported();
        }

        @Override
        public void putLong(byte[] key, long value) throws SQLException {
            throw unsupported();
        }

        @Override
        public void delete(byte[] key) throws SQLException {
            throw unsupported();
        }

        @Override
        public void deleteRange(byte[] startKey, byte[] endKey) throws SQLException {
            throw unsupported();
        }

        @Override
        public byte[] get(byte cfId, byte[] key) throws SQLException {
            throw unsupported();
        }

        @Override
        public void put(byte cfId, byte[] key, byte[] value) throws SQLException {
            throw unsupported();
        }

        @Override
        public void putLong(byte cfId, byte[] key, long value) throws SQLException {
            throw unsupported();
        }

        @Override
        public void delete(byte cfId, byte[] key) throws SQLException {
            throw unsupported();
        }

        @Override
        public void deleteRange(byte cfId, byte[] startKey, byte[] endKey) throws SQLException {
            throw unsupported();
        }

        @Override
        public void checkpoint(String targetDir) throws IOException {
            throw unsupported();
        }

        @Override
        public void restore(String sourceDir) throws IOException {
            throw unsupported();
        }

        @Override
        public void writeBatch(WriteBatchConsumer consumer) throws SQLException {
            throw unsupported();
        }

        @Override
        public void rollback(long txnId) throws SQLException {
            throw unsupported();
        }

        @Override
        public CompletableFuture<Void> commitAsync(long txnId, long commitTs, List<Meta> metas) throws SQLException {
            throw unsupported();
        }

        @Override
        public VersionScanSource openVersionScanSource(ScanDirection direction) {
            throw unsupported();
        }

        @Override
        public VersionScanSource openVersionScanSource(byte cfId, ScanDirection direction) {
            throw unsupported();
        }

        @Override
        public void close() {
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by generator tests");
        }
    }
}
