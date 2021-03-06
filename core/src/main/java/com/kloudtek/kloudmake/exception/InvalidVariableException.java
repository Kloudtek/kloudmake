/*
 * Copyright (c) 2015. Kelewan Technologies Ltd
 */

package com.kloudtek.kloudmake.exception;

import org.slf4j.Logger;

public class InvalidVariableException extends KMRuntimeException {
    public InvalidVariableException() {
    }

    public InvalidVariableException(String message) {
        super(message);
    }

    public InvalidVariableException(Logger logger, String message) {
        super(logger, message);
    }

    public InvalidVariableException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidVariableException(Logger logger, String message, Throwable cause) {
        super(logger, message, cause);
    }

    public InvalidVariableException(Throwable cause) {
        super(cause);
    }

    public InvalidVariableException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
