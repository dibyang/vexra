/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: Alessandro Ventura
 */
package org.adb.api;

import org.adb.security.auth.AuthenticationInfo;
import org.adb.security.auth.Configurable;

/**
 * A class that implement this interface can be used to validate credentials
 * provided by client.
 * <p>
 * <b>This feature is experimental and subject to change</b>
 * </p>
 */
public interface CredentialsValidator extends Configurable {

    /**
     * Validate user credential.
     *
     * @param authenticationInfo
     *            = authentication info
     * @return true if credentials are valid, otherwise false
     * @throws Exception
     *             any exception occurred (invalid credentials or internal
     *             issue) prevent user login
     */
    boolean validateCredentials(AuthenticationInfo authenticationInfo) throws Exception;

}
