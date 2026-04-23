package net.xdob.vexra.ldb.table;

import net.xdob.vexra.ldb.Options;
import net.xdob.vexra.ldb.util.Slice;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Comparator;

public class MMapTableTest
        extends TableTest
{
    @Override
    protected Table createTable(String name, FileChannel fileChannel,
                                Comparator<Slice> comparator,
                                boolean verifyChecksums,
                                Options options)
            throws IOException
    {
        return new MMapTable(name, fileChannel, comparator, verifyChecksums, options, null);
    }
}
