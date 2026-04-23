/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.adb.engine;

import org.adb.api.JavaObjectSerializer;
import org.adb.util.TimeZoneProvider;
import org.adb.value.ValueTimestampTimeZone;

/**
 * Provides information for type casts and comparison operations.
 */
public interface CastDataProvider {

    /**
     * Returns the current timestamp with maximum resolution. The value must be
     * the same within a transaction or within execution of a command.
     *
     * @return the current timestamp for CURRENT_TIMESTAMP(9)
     */
    ValueTimestampTimeZone currentTimestamp();

    /**
     * Returns the current time zone.
     *
     * @return the current time zone
     */
    TimeZoneProvider currentTimeZone();

    /**
     * Returns the database mode.
     *
     * @return the database mode
     */
    Mode getMode();

    /**
     * Returns the custom Java object serializer, or {@code null}.
     *
     * @return the custom Java object serializer, or {@code null}
     */
    JavaObjectSerializer getJavaObjectSerializer();

    /**
     * Returns are ENUM values 0-based.
     *
     * @return are ENUM values 0-based
     */
    boolean zeroBasedEnums();

}
