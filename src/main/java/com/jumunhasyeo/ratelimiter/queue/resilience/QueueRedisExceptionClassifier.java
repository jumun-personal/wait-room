package com.jumunhasyeo.ratelimiter.queue.resilience;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

@Component
public class QueueRedisExceptionClassifier {

    public RuntimeException wrapIfTransient(String operation, RuntimeException exception) {
        if (exception instanceof QueueRedisTransientException) {
            return exception;
        }
        if (isTransient(exception)) {
            return new QueueRedisTransientException(operation, exception);
        }
        return exception;
    }

    public boolean isTransient(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof QueueRedisTransientException
                    || current instanceof RedisConnectionFailureException
                    || current instanceof QueryTimeoutException
                    || current instanceof TransientDataAccessException
                    || current instanceof RedisCommandTimeoutException
                    || current instanceof RedisConnectionException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof TimeoutException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && hasTransientRedisMessage(message)) {
                return true;
            }

            if (current instanceof RedisCommandExecutionException && message != null && hasTransientRedisMessage(message)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTransientRedisMessage(String message) {
        String normalized = message.toUpperCase(Locale.ROOT);
        return normalized.contains("READONLY")
                || normalized.contains("MASTERDOWN")
                || normalized.contains("LOADING")
                || normalized.contains("TRYAGAIN")
                || normalized.contains("CLUSTERDOWN")
                || normalized.contains("NO SUCH MASTER")
                || normalized.contains("NODE IS NOT EMPTY")
                || normalized.contains("CONNECTION RESET")
                || normalized.contains("CONNECTION REFUSED");
    }
}
