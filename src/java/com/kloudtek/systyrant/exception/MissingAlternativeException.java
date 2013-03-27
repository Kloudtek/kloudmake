/*
 * Copyright (c) 2013 KloudTek Ltd
 */

package com.kloudtek.systyrant.exception;

import org.slf4j.Logger;

public class MissingAlternativeException extends STRuntimeException {
    public MissingAlternativeException() {
    }

    public MissingAlternativeException(String message) {
        super(message);
    }

    public MissingAlternativeException(Logger logger, String message) {
        super(logger, message);
    }

    public MissingAlternativeException(String message, Throwable cause) {
        super(message, cause);
    }

    public MissingAlternativeException(Logger logger, String message, Throwable cause) {
        super(logger, message, cause);
    }

    public MissingAlternativeException(Throwable cause) {
        super(cause);
    }

    public MissingAlternativeException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
