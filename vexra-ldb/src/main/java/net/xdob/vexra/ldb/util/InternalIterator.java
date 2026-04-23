package net.xdob.vexra.ldb.util;

import net.xdob.vexra.ldb.impl.InternalKey;
import net.xdob.vexra.ldb.impl.SeekingIterator;

/**
 * <p>A common interface for internal iterators.</p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public interface InternalIterator
    extends SeekingIterator<InternalKey, Slice> {
}
